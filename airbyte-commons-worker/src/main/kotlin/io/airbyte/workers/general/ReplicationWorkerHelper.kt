/*
 * Copyright (c) 2020-2024 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.workers.general

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.annotations.VisibleForTesting
import io.airbyte.api.client.AirbyteApiClient
import io.airbyte.api.client.WorkloadApiClient
import io.airbyte.api.client.model.generated.ActorType
import io.airbyte.api.client.model.generated.DestinationIdRequestBody
import io.airbyte.api.client.model.generated.ResolveActorDefinitionVersionRequestBody
import io.airbyte.api.client.model.generated.SourceIdRequestBody
import io.airbyte.commons.concurrency.VoidCallable
import io.airbyte.commons.converters.ThreadedTimeTracker
import io.airbyte.commons.helper.DockerImageName
import io.airbyte.commons.io.LineGobbler
import io.airbyte.config.FailureReason
import io.airbyte.config.PerformanceMetrics
import io.airbyte.config.ReplicationAttemptSummary
import io.airbyte.config.ReplicationOutput
import io.airbyte.config.StandardSyncSummary.ReplicationStatus
import io.airbyte.config.SyncStats
import io.airbyte.config.WorkerDestinationConfig
import io.airbyte.metrics.lib.ApmTraceUtils
import io.airbyte.metrics.lib.MetricAttribute
import io.airbyte.metrics.lib.MetricClient
import io.airbyte.metrics.lib.MetricClientFactory
import io.airbyte.metrics.lib.MetricTags
import io.airbyte.metrics.lib.OssMetricsRegistry
import io.airbyte.persistence.job.models.ReplicationInput
import io.airbyte.protocol.models.AirbyteMessage
import io.airbyte.protocol.models.AirbyteMessage.Type
import io.airbyte.protocol.models.AirbyteStateMessage
import io.airbyte.protocol.models.AirbyteStateStats
import io.airbyte.protocol.models.AirbyteTraceMessage
import io.airbyte.protocol.models.ConfiguredAirbyteCatalog
import io.airbyte.workers.WorkerUtils
import io.airbyte.workers.context.ReplicationContext
import io.airbyte.workers.context.ReplicationFeatureFlags
import io.airbyte.workers.exception.WorkloadHeartbeatException
import io.airbyte.workers.helper.FailureHelper
import io.airbyte.workers.helper.StreamStatusCompletionTracker
import io.airbyte.workers.internal.AirbyteDestination
import io.airbyte.workers.internal.AirbyteMapper
import io.airbyte.workers.internal.AirbyteSource
import io.airbyte.workers.internal.AnalyticsMessageTracker
import io.airbyte.workers.internal.DestinationTimeoutMonitor
import io.airbyte.workers.internal.FieldSelector
import io.airbyte.workers.internal.HeartbeatTimeoutChaperone
import io.airbyte.workers.internal.bookkeeping.AirbyteMessageOrigin
import io.airbyte.workers.internal.bookkeeping.AirbyteMessageTracker
import io.airbyte.workers.internal.bookkeeping.events.ReplicationAirbyteMessageEvent
import io.airbyte.workers.internal.bookkeeping.events.ReplicationAirbyteMessageEventPublishingHelper
import io.airbyte.workers.internal.bookkeeping.getPerStreamStats
import io.airbyte.workers.internal.bookkeeping.getTotalStats
import io.airbyte.workers.internal.bookkeeping.streamstatus.StreamStatusTracker
import io.airbyte.workers.internal.bookkeeping.streamstatus.StreamStatusTrackerFactory
import io.airbyte.workers.internal.exception.DestinationException
import io.airbyte.workers.internal.exception.SourceException
import io.airbyte.workers.internal.syncpersistence.SyncPersistence
import io.airbyte.workers.models.StateWithId.attachIdToStateMessageFromSource
import io.airbyte.workload.api.client.model.generated.WorkloadHeartbeatRequest
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micronaut.http.HttpStatus
import org.apache.commons.io.FileUtils
import org.slf4j.MDC
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.Optional
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import io.airbyte.workload.api.client.generated.infrastructure.ClientException as GeneratedClientException

private val logger = KotlinLogging.logger { }

class ReplicationWorkerHelper(
  private val fieldSelector: FieldSelector,
  private val mapper: AirbyteMapper,
  private val messageTracker: AirbyteMessageTracker,
  private val syncPersistence: SyncPersistence,
  private val replicationAirbyteMessageEventPublishingHelper: ReplicationAirbyteMessageEventPublishingHelper,
  private val timeTracker: ThreadedTimeTracker,
  private val onReplicationRunning: VoidCallable,
  private val workloadApiClient: WorkloadApiClient,
  private val workloadEnabled: Boolean,
  private val analyticsMessageTracker: AnalyticsMessageTracker,
  private val workloadId: Optional<String>,
  private val airbyteApiClient: AirbyteApiClient,
  private val streamStatusCompletionTracker: StreamStatusCompletionTracker,
  private val streamStatusTrackerFactory: StreamStatusTrackerFactory,
) {
  private val metricClient = MetricClientFactory.getMetricClient()
  private val metricAttrs: MutableList<MetricAttribute> = mutableListOf()
  private val replicationFailures: MutableList<FailureReason> = Collections.synchronizedList(mutableListOf())
  private val _cancelled = AtomicBoolean()
  private val hasFailed = AtomicBoolean()
  private val _shouldAbort = AtomicBoolean()

  // The nuance between cancelled and abort is that cancelled is triggered by an external factor (typically user cancellation) while
  // abort is the result of an internal detection that we should abort (typically heartbeat failure).
  val cancelled: Boolean
    get() = _cancelled.get()
  val shouldAbort: Boolean
    get() = _shouldAbort.get() || _cancelled.get()

  private var recordsRead: Long = 0
  private var destinationConfig: WorkerDestinationConfig? = null
  private var ctx: ReplicationContext? = null
  private lateinit var replicationFeatureFlags: ReplicationFeatureFlags
  private lateinit var streamStatusTracker: StreamStatusTracker

  fun markCancelled(): Unit = _cancelled.set(true)

  fun markFailed(): Unit = hasFailed.set(true)

  fun abort(): Unit = _shouldAbort.set(true)

  fun markReplicationRunning() {
    onReplicationRunning.call()
  }

  fun getWorkloadStatusHeartbeat(mdc: Map<String, String>): Runnable {
    return getWorkloadStatusHeartbeat(Duration.ofSeconds(replicationFeatureFlags.workloadHeartbeatRate.toLong()), workloadId, mdc)
  }

  private fun getWorkloadStatusHeartbeat(
    heartbeatInterval: Duration,
    workloadId: Optional<String>,
    mdc: Map<String, String>,
  ): Runnable {
    return Runnable {
      MDC.setContextMap(mdc)
      logger.info { "Starting workload heartbeat" }
      var lastSuccessfulHeartbeat: Instant = Instant.now()
      val heartbeatTimeoutDuration: Duration = Duration.ofMinutes(replicationFeatureFlags.workloadHeartbeatTimeoutInMinutes)
      do {
        Thread.sleep(heartbeatInterval.toMillis())
        ctx?.let {
          try {
            if (workloadId.isEmpty) {
              throw RuntimeException("workloadId should always be present")
            }
            logger.debug { "Sending workload heartbeat" }
            workloadApiClient.workloadApi.workloadHeartbeat(
              WorkloadHeartbeatRequest(workloadId.get()),
            )
            lastSuccessfulHeartbeat = Instant.now()
          } catch (e: Exception) {
            /**
             * The WorkloadApi returns responseCode "410" from the heartbeat endpoint if
             * Workload should stop because it is no longer expected to be running.
             * See [io.airbyte.workload.api.WorkloadApi.workloadHeartbeat]
             */
            if (e is GeneratedClientException && e.statusCode == HttpStatus.GONE.code) {
              metricClient.count(OssMetricsRegistry.HEARTBEAT_TERMINAL_SHUTDOWN, 1, *metricAttrs.toTypedArray())
              markCancelled()
              return@Runnable
            } else if (Duration.between(lastSuccessfulHeartbeat, Instant.now()) > heartbeatTimeoutDuration) {
              logger.warn(e) { "Have not been able to update heartbeat for more than the timeout duration, shutting down heartbeat" }
              metricClient.count(OssMetricsRegistry.HEARTBEAT_CONNECTIVITY_FAILURE_SHUTDOWN, 1, *metricAttrs.toTypedArray())
              markFailed()
              abort()
              trackFailure(WorkloadHeartbeatException("Workload Heartbeat Error", e))
              return@Runnable
            }
            logger.warn(e) { "Error while trying to heartbeat, re-trying" }
          }
        }
      } while (true)
    }
  }

  fun initialize(
    ctx: ReplicationContext,
    replicationFeatureFlags: ReplicationFeatureFlags,
    jobRoot: Path,
    configuredAirbyteCatalog: ConfiguredAirbyteCatalog,
  ) {
    timeTracker.trackReplicationStartTime()

    this.ctx = ctx
    this.replicationFeatureFlags = replicationFeatureFlags
    this.streamStatusTracker = streamStatusTrackerFactory.create(ctx)

    analyticsMessageTracker.ctx = ctx

    with(metricAttrs) {
      clear()
      addAll(toConnectionAttrs(ctx))
    }

    ApmTraceUtils.addTagsToTrace(ctx.connectionId, ctx.attempt.toLong(), ctx.jobId.toString(), jobRoot)

    val supportRefreshes =
      airbyteApiClient.actorDefinitionVersionApi.resolveActorDefinitionVersionByTag(
        ResolveActorDefinitionVersionRequestBody(
          actorDefinitionId = ctx.destinationDefinitionId,
          actorType = ActorType.DESTINATION,
          dockerImageTag = DockerImageName.extractTag(ctx.destinationImage),
        ),
      ).supportRefreshes
    streamStatusCompletionTracker.startTracking(configuredAirbyteCatalog, supportRefreshes)
  }

  fun startDestination(
    destination: AirbyteDestination,
    replicationInput: ReplicationInput,
    jobRoot: Path,
  ) {
    timeTracker.trackDestinationWriteStartTime()
    destinationConfig =
      WorkerUtils.syncToWorkerDestinationConfig(replicationInput)
        .apply { catalog = mapper.mapCatalog(catalog) }

    try {
      destination.start(destinationConfig, jobRoot)
    } catch (e: Exception) {
      throw RuntimeException(e)
    }
  }

  fun startSource(
    source: AirbyteSource,
    replicationInput: ReplicationInput,
    jobRoot: Path,
  ) {
    timeTracker.trackSourceReadStartTime()

    val sourceConfig =
      WorkerUtils.syncToWorkerSourceConfig(replicationInput)
        .also { fieldSelector.populateFields(it.catalog) }

    try {
      source.start(sourceConfig, jobRoot, ctx?.connectionId)
    } catch (e: Exception) {
      throw RuntimeException(e)
    }
  }

  fun endOfReplication() {
    timeTracker.trackReplicationEndTime()
    // This is triggered to allow emission of successful state checksum events
    // in case no checksum errors were found throughout the sync
    messageTracker.endOfReplication((!_cancelled.get() && !hasFailed.get() && !shouldAbort))

    analyticsMessageTracker.flush()
  }

  fun endOfSource() {
    val context = requireNotNull(ctx)

    logger.info {
      val bytes = FileUtils.byteCountToDisplaySize(messageTracker.syncStatsTracker.getTotalBytesEmitted())
      "Total records read: $recordsRead ($bytes)"
    }

    fieldSelector.reportMetrics(context.sourceId)
    timeTracker.trackSourceReadEndTime()
  }

  fun endOfDestination() {
    timeTracker.trackDestinationWriteEndTime()
  }

  fun trackFailure(t: Throwable) {
    val context = requireNotNull(ctx)

    replicationFailures.add(getFailureReason(t, context.jobId, context.attempt))
  }

  fun processMessageFromDestination(destinationRawMessage: AirbyteMessage) {
    val message = mapper.revertMap(destinationRawMessage)
    internalProcessMessageFromDestination(message)
  }

  fun getStreamStatusToSend(exitValue: Int): List<AirbyteMessage> {
    return streamStatusCompletionTracker.finalize(exitValue, mapper)
  }

  @JvmOverloads
  @Throws(JsonProcessingException::class)
  fun getReplicationOutput(performanceMetrics: PerformanceMetrics? = null): ReplicationOutput {
    val outputStatus: ReplicationStatus =
      when {
        _cancelled.get() -> ReplicationStatus.CANCELLED
        hasFailed.get() -> ReplicationStatus.FAILED
        else -> ReplicationStatus.COMPLETED
      }

    val hasReplicationCompleted = outputStatus == ReplicationStatus.COMPLETED
    val totalSyncStats = getTotalStats(timeTracker, hasReplicationCompleted)
    val streamSyncStats = messageTracker.syncStatsTracker.getPerStreamStats(hasReplicationCompleted)

    if (!hasReplicationCompleted && messageTracker.syncStatsTracker.getUnreliableStateTimingMetrics()) {
      logger.warn { "Could not reliably determine committed record counts, committed record stats will be set to null" }
    }

    val summary =
      ReplicationAttemptSummary()
        .withStatus(outputStatus)
        // TODO records and bytes synced should no longer be used as we are consuming total stats, we should make a pass to remove them.
        .withRecordsSynced(messageTracker.syncStatsTracker.getTotalRecordsCommitted())
        .withBytesSynced(messageTracker.syncStatsTracker.getTotalBytesCommitted())
        .withTotalStats(totalSyncStats)
        .withStreamStats(streamSyncStats)
        .withStartTime(timeTracker.replicationStartTime)
        .withEndTime(System.currentTimeMillis())
        .withPerformanceMetrics(performanceMetrics)

    val output =
      ReplicationOutput()
        .withReplicationAttemptSummary(summary)
        .withOutputCatalog(requireNotNull(destinationConfig).catalog)

    val failures: List<FailureReason> = getFailureReasons(replicationFailures, output)

    ObjectMapper().also { mapper ->
      logger.info { "sync summary: ${mapper.writerWithDefaultPrettyPrinter().writeValueAsString(summary)}" }
      logger.info { "failures: ${mapper.writerWithDefaultPrettyPrinter().writeValueAsString(failures)}" }
    }

    // Metric to help investigating https://github.com/airbytehq/airbyte/issues/34567
    if (failures.any { f -> f.failureOrigin.equals("destination") && f.internalMessage.contains("Unable to deserialize PartialAirbyteMessage") }) {
      metricClient.count(OssMetricsRegistry.DESTINATION_DESERIALIZATION_ERROR, 1, *metricAttrs.toTypedArray())
    }

    LineGobbler.endSection("REPLICATION")
    return output
  }

  @VisibleForTesting
  fun internalProcessMessageFromSource(sourceRawMessage: AirbyteMessage): AirbyteMessage {
    val context = requireNotNull(ctx)

    fieldSelector.filterSelectedFields(sourceRawMessage)
    fieldSelector.validateSchema(sourceRawMessage)
    messageTracker.acceptFromSource(sourceRawMessage)
    streamStatusTracker.track(sourceRawMessage)
    if (isAnalyticsMessage(sourceRawMessage)) {
      analyticsMessageTracker.addMessage(sourceRawMessage, AirbyteMessageOrigin.SOURCE)
    }

    handleControlMessage(sourceRawMessage, context, AirbyteMessageOrigin.SOURCE)

    recordsRead += 1
    if (recordsRead % 5000 == 0L) {
      logger.info {
        val bytes = FileUtils.byteCountToDisplaySize(messageTracker.syncStatsTracker.getTotalBytesEmitted())
        "Records read: $recordsRead ($bytes)"
      }
    }

    if (sourceRawMessage.type == Type.STATE) {
      metricClient.count(OssMetricsRegistry.STATE_PROCESSED_FROM_SOURCE, 1, *metricAttrs.toTypedArray())
      recordStateStatsMetrics(metricClient, sourceRawMessage.state, AirbyteMessageOrigin.SOURCE, ctx!!)
    }

    return sourceRawMessage
  }

  private fun handleControlMessage(
    rawMessage: AirbyteMessage,
    context: ReplicationContext,
    origin: AirbyteMessageOrigin,
  ) {
    if (rawMessage.type == Type.CONTROL) {
      replicationAirbyteMessageEventPublishingHelper
        .publishEvent(ReplicationAirbyteMessageEvent(origin, rawMessage, context))
    }
  }

  @VisibleForTesting
  fun internalProcessMessageFromDestination(destinationRawMessage: AirbyteMessage) {
    val context = requireNotNull(ctx)

    streamStatusTracker.track(destinationRawMessage)

    logger.debug { "State in ReplicationWorkerHelper from destination: $destinationRawMessage" }

    messageTracker.acceptFromDestination(destinationRawMessage)
    if (isAnalyticsMessage(destinationRawMessage)) {
      analyticsMessageTracker.addMessage(destinationRawMessage, AirbyteMessageOrigin.DESTINATION)
    }

    if (destinationRawMessage.type == Type.STATE) {
      val airbyteStateMessage = destinationRawMessage.state
      recordStateStatsMetrics(metricClient, airbyteStateMessage, AirbyteMessageOrigin.DESTINATION, ctx!!)
      syncPersistence.persist(context.connectionId, destinationRawMessage.state)
      metricClient.count(OssMetricsRegistry.STATE_PROCESSED_FROM_DESTINATION, 1, *metricAttrs.toTypedArray())
    }

    handleControlMessage(destinationRawMessage, context, AirbyteMessageOrigin.DESTINATION)
  }

  // TODO convert to AirbyteMessage? when fully converted to kotlin
  fun processMessageFromSource(sourceRawMessage: AirbyteMessage): Optional<AirbyteMessage> {
    // internally we always want to deal with the state message we got from the
    // source, so we only modify the state message after processing it, right before we send it to the
    // destination
    return attachIdToStateMessageFromSource(sourceRawMessage)
      .let { internalProcessMessageFromSource(it) }
      .let { mapper.mapMessage(it) }
      .let { Optional.ofNullable(it) }
  }

  fun isWorkerV2TestEnabled(): Boolean {
    return workloadEnabled
  }

  fun getSourceDefinitionIdForSourceId(sourceId: UUID): UUID {
    return airbyteApiClient.sourceApi.getSource(SourceIdRequestBody(sourceId = sourceId)).sourceDefinitionId
  }

  fun getDestinationDefinitionIdForDestinationId(destinationId: UUID): UUID {
    return airbyteApiClient.destinationApi.getDestination(DestinationIdRequestBody(destinationId = destinationId)).destinationDefinitionId
  }

  private fun getTotalStats(
    timeTracker: ThreadedTimeTracker,
    hasReplicationCompleted: Boolean,
  ): SyncStats {
    return messageTracker.syncStatsTracker.getTotalStats(hasReplicationCompleted).apply {
      replicationStartTime = timeTracker.replicationStartTime
      replicationEndTime = timeTracker.replicationEndTime
      sourceReadStartTime = timeTracker.sourceReadStartTime
      sourceReadEndTime = timeTracker.sourceReadEndTime
      destinationWriteStartTime = timeTracker.destinationWriteStartTime
      destinationWriteEndTime = timeTracker.destinationWriteEndTime
    }
  }

  private fun getFailureReasons(
    replicationFailures: List<FailureReason>,
    output: ReplicationOutput,
  ): List<FailureReason> {
    val context = requireNotNull(ctx)

    val failures = mutableListOf<FailureReason>()
    // only .setFailures() if a failure occurred or if there is an AirbyteErrorTraceMessage
    failures.addAll(messageTracker.errorTraceMessageFailure(context.jobId, context.attempt))

    failures.addAll(replicationFailures)

    if (failures.isNotEmpty()) {
      output.failures = failures
    }

    return failures
  }

  companion object {
    @JvmStatic
    @VisibleForTesting
    fun getFailureReason(
      ex: Throwable,
      jobId: Long?,
      attempt: Int?,
    ): FailureReason =
      when (ex) {
        is SourceException -> FailureHelper.sourceFailure(ex, jobId, attempt)
        is DestinationException -> FailureHelper.destinationFailure(ex, jobId, attempt)
        is HeartbeatTimeoutChaperone.HeartbeatTimeoutException ->
          FailureHelper.sourceHeartbeatFailure(
            ex,
            jobId,
            attempt,
            ex.humanReadableThreshold,
            ex.humanReadableTimeSinceLastRec,
          )
        is DestinationTimeoutMonitor.TimeoutException ->
          FailureHelper.destinationTimeoutFailure(
            ex,
            jobId,
            attempt,
            ex.humanReadableThreshold,
            ex.humanReadableTimeSinceLastAction,
          )
        is WorkloadHeartbeatException -> FailureHelper.platformFailure(ex, jobId, attempt, ex.message)
        else -> FailureHelper.replicationFailure(ex, jobId, attempt)
      }
  }
}

private fun isAnalyticsMessage(msg: AirbyteMessage): Boolean = msg.type == Type.TRACE && msg.trace.type == AirbyteTraceMessage.Type.ANALYTICS

private fun toConnectionAttrs(ctx: ReplicationContext?): List<MetricAttribute> {
  if (ctx == null) {
    return listOf()
  }

  return buildList {
    ctx.connectionId?.let { add(MetricAttribute(MetricTags.CONNECTION_ID, it.toString())) }
    ctx.jobId?.let { add(MetricAttribute(MetricTags.JOB_ID, it.toString())) }
    ctx.attempt?.let { add(MetricAttribute(MetricTags.ATTEMPT_NUMBER, it.toString())) }
  }
}

private fun extractStateRecordCount(stats: AirbyteStateStats?): Double {
  return stats?.recordCount ?: 0.0
}

private fun recordStateStatsMetrics(
  metricClient: MetricClient,
  stateMessage: AirbyteStateMessage,
  messageOrigin: AirbyteMessageOrigin,
  ctx: ReplicationContext,
) {
  // Only record the destination stats for state messages coming from the destination.
  // The destination stats will always be blank for state messages coming from the source
  if (messageOrigin == AirbyteMessageOrigin.DESTINATION && stateMessage.destinationStats != null) {
    recordStateStatsMetric(
      metricClient,
      stateMessage.destinationStats,
      messageOrigin,
      AirbyteMessageOrigin.DESTINATION,
      ctx,
    )
  }

  if (stateMessage.sourceStats != null) {
    recordStateStatsMetric(metricClient, stateMessage.sourceStats, messageOrigin, AirbyteMessageOrigin.SOURCE, ctx)
  }
}

private fun recordStateStatsMetric(
  metricClient: MetricClient,
  stats: AirbyteStateStats,
  messageOrigin: AirbyteMessageOrigin,
  statsType: AirbyteMessageOrigin,
  ctx: ReplicationContext,
) {
  metricClient.gauge(
    OssMetricsRegistry.SYNC_STATE_RECORD_COUNT,
    extractStateRecordCount(stats),
    *toConnectionAttrs(ctx).toTypedArray(),
    *buildList {
      ctx.sourceImage?.let { add(MetricAttribute(MetricTags.SOURCE_IMAGE, it)) }
      ctx.destinationImage?.let {
        add(MetricAttribute(MetricTags.DESTINATION_IMAGE, it))
        add(MetricAttribute(MetricTags.AIRBYTE_MESSAGE_ORIGIN, messageOrigin.name))
      }
      add(MetricAttribute(MetricTags.RECORD_COUNT_TYPE, statsType.name))
    }.toTypedArray(),
  )
}
