/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.workers.internal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.airbyte.featureflag.FeatureFlagClient;
import io.airbyte.featureflag.ShouldFailSyncIfHeartbeatFailure;
import io.airbyte.featureflag.TestClient;
import io.airbyte.metrics.lib.MetricAttribute;
import io.airbyte.metrics.lib.MetricClient;
import io.airbyte.metrics.lib.MetricTags;
import io.airbyte.metrics.lib.OssMetricsRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class HeartBeatTimeoutChaperoneTest {

  private final HeartbeatMonitor heartbeatMonitor = mock(HeartbeatMonitor.class);
  private final Duration timeoutCheckDuration = Duration.ofMillis(1);

  private final FeatureFlagClient featureFlagClient = mock(TestClient.class);
  private final UUID workspaceId = UUID.randomUUID();
  private final UUID connectionId = UUID.randomUUID();
  private final MetricClient metricClient = mock(MetricClient.class);
  private final ExecutorService executorService = Executors.newFixedThreadPool(4);

  private final HeartbeatTimeoutChaperone heartbeatTimeoutChaperone = new HeartbeatTimeoutChaperone(
      heartbeatMonitor,
      timeoutCheckDuration,
      featureFlagClient,
      workspaceId,
      connectionId,
      metricClient);

  @Test
  void testFailHeartbeat() {
    when(featureFlagClient.enabled(eq(ShouldFailSyncIfHeartbeatFailure.INSTANCE), any())).thenReturn(true);
    when(heartbeatMonitor.isBeating()).thenReturn(Optional.of(false));
    assertThrows(HeartbeatTimeoutChaperone.HeartbeatTimeoutException.class,
        () -> heartbeatTimeoutChaperone.runWithHeartbeatThread(() -> {
          try {
            Thread.sleep(Long.MAX_VALUE);
          } catch (final InterruptedException e) {
            throw new RuntimeException(e);
          }
        }, executorService).run());
  }

  @Test
  void testNotFailingHeartbeat() {
    when(featureFlagClient.enabled(eq(ShouldFailSyncIfHeartbeatFailure.INSTANCE), any())).thenReturn(true);
    when(heartbeatMonitor.isBeating()).thenReturn(Optional.of(true));
    assertDoesNotThrow(() -> heartbeatTimeoutChaperone.runWithHeartbeatThread(() -> {
      try {
        Thread.sleep(10);
      } catch (final InterruptedException e) {
        throw new RuntimeException(e);
      }
    }, executorService).run());
  }

  @Test
  void testFailHeartbeatWithoutFeatureFlag() {
    when(featureFlagClient.enabled(eq(ShouldFailSyncIfHeartbeatFailure.INSTANCE), any())).thenReturn(false);
    when(heartbeatMonitor.isBeating()).thenReturn(Optional.of(false));
    assertDoesNotThrow(() -> heartbeatTimeoutChaperone.runWithHeartbeatThread(() -> {
      try {
        Thread.sleep(100);
      } catch (final InterruptedException e) {
        throw new RuntimeException(e);
      }
    }, executorService).run());
    verify(metricClient, times(1)).count(OssMetricsRegistry.SOURCE_HEARTBEAT_FAILURE, 1,
        new MetricAttribute(MetricTags.CONNECTION_ID, connectionId.toString()));
  }

}
