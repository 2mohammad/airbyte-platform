/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.commons.logging;

import static io.airbyte.commons.constants.AirbyteCatalogConstants.LOCAL_SECRETS_MASKS_PATH;

import com.fasterxml.jackson.core.type.TypeReference;
import io.airbyte.commons.constants.AirbyteSecretConstants;
import io.airbyte.commons.json.Jsons;
import io.airbyte.commons.yaml.Yamls;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.rewrite.RewritePolicy;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.apache.logging.log4j.status.StatusLogger;

/**
 * Custom Log4j2 {@link RewritePolicy} used to intercept all log messages and mask any JSON
 * properties in the message that match the list of maskable properties.
 * <p>
 * The maskable properties file is generated by a Gradle task in the {@code :airbyte-config:specs}
 * project. The file is named {@code specs_secrets_mask.yaml} and is located in the
 * {@code src/main/resources/seed} directory of the {@link :airbyte-config:init} project.
 */
@Plugin(name = "MaskedDataInterceptor",
        category = "Core",
        elementType = "rewritePolicy",
        printObject = true)
public class MaskedDataInterceptor implements RewritePolicy {

  protected static final Logger logger = StatusLogger.getLogger();

  /**
   * The pattern used to determine if a message contains sensitive data.
   */
  private final Optional<String> pattern;

  @PluginFactory
  public static MaskedDataInterceptor createPolicy(
                                                   @PluginAttribute(value = "specMaskFile",
                                                                    defaultString = LOCAL_SECRETS_MASKS_PATH) final String specMaskFile) {
    return new MaskedDataInterceptor(specMaskFile);
  }

  private MaskedDataInterceptor(final String specMaskFile) {
    this.pattern = buildPattern(specMaskFile);
  }

  @Override
  public LogEvent rewrite(final LogEvent source) {
    return Log4jLogEvent.newBuilder()
        .setLoggerName(source.getLoggerName())
        .setMarker(source.getMarker())
        .setLoggerFqcn(source.getLoggerFqcn())
        .setLevel(source.getLevel())
        .setMessage(new SimpleMessage(applyMask(source.getMessage().getFormattedMessage())))
        .setThrown(source.getThrown())
        .setContextMap(source.getContextMap())
        .setContextStack(source.getContextStack())
        .setThreadName(source.getThreadName())
        .setSource(source.getSource())
        .setTimeMillis(source.getTimeMillis())
        .build();
  }

  /**
   * Applies the mask to the message, if necessary.
   *
   * @param message The log message.
   * @return The possibly masked log message.
   */
  private String applyMask(final String message) {
    if (pattern.isPresent()) {
      return message.replaceAll(pattern.get(), "\"$1\":\"" + AirbyteSecretConstants.SECRETS_MASK + "\"");
    } else {
      return message;
    }
  }

  /**
   * Loads the maskable properties from the provided file.
   *
   * @param specMaskFile The spec mask file.
   * @return The set of maskable properties.
   */
  private Set<String> getMaskableProperties(final String specMaskFile) {
    logger.info("Loading mask data from '{}", specMaskFile);
    try {
      final String maskFileContents = IOUtils.toString(getClass().getResourceAsStream(specMaskFile), Charset.defaultCharset());
      final Map<String, Set<String>> properties = Jsons.object(Yamls.deserialize(maskFileContents), new TypeReference<>() {});
      return properties.getOrDefault("properties", Set.of());
    } catch (final Exception e) {
      logger.error("Unable to load mask data from '{}': {}.", specMaskFile, e.getMessage());
      return Set.of();
    }
  }

  /**
   * Builds the maskable property matching pattern.
   *
   * @param specMaskFile The spec mask file.
   * @return The regular expression pattern used to find maskable properties.
   */
  private Optional<String> buildPattern(final String specMaskFile) {
    final Set<String> maskableProperties = getMaskableProperties(specMaskFile);
    return !maskableProperties.isEmpty() ? Optional.of(generatePattern(maskableProperties)) : Optional.empty();
  }

  /**
   * Generates the property matching pattern string from the provided set of properties.
   *
   * @param properties The set of properties to match.
   * @return The generated regular expression pattern used to match the maskable properties.
   */
  private String generatePattern(final Set<String> properties) {
    final StringBuilder builder = new StringBuilder();
    builder.append("(?i)"); // case insensitive
    builder.append("\"(");
    builder.append(properties.stream().collect(Collectors.joining("|")));
    builder.append(")\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|\\[[^]\\[]*]|\\d+)");
    return builder.toString();
  }

}
