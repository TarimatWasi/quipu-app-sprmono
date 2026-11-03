package com.tarimatwasi.quipu.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * TAR-278 (ADR-F5): a log written through the {@code OpenTelemetryAppender} reaches the exporter
 * through the same batch processor that production uses. If the appender and the SDK of the Boot
 * BOM do not match (the appender 2.33.0-alpha against the SDK 1.62.0), the worker thread of the
 * batch dies with a NoClassDefFoundError and nothing is exported.
 */
class OpenTelemetryLoggingConfigTest {

  /** Keeps what the batch processor exports, instead of sending it over OTLP. */
  private static final class CollectingExporter implements LogRecordExporter {

    final List<LogRecordData> exported = new CopyOnWriteArrayList<>();

    @Override
    public CompletableResultCode export(Collection<LogRecordData> logs) {
      exported.addAll(logs);
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
      return CompletableResultCode.ofSuccess();
    }
  }

  @Test
  void openTelemetryAppenderInstaller_logThroughTheAppender_reachesTheExporter() throws Exception {
    CollectingExporter exporter = new CollectingExporter();
    SdkLoggerProvider loggerProvider =
        SdkLoggerProvider.builder()
            .addLogRecordProcessor(BatchLogRecordProcessor.builder(exporter).build())
            .build();
    OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setLoggerProvider(loggerProvider).build();
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    Logger logger = context.getLogger("tar278.otel");
    OpenTelemetryAppender appender = new OpenTelemetryAppender();
    appender.setContext(context);
    appender.start();
    logger.addAppender(appender);
    logger.setAdditive(false);
    try {
      new OpenTelemetryLoggingConfig().openTelemetryAppenderInstaller(sdk).afterPropertiesSet();

      logger.info("A log for Grafana Cloud");
      assertThat(loggerProvider.forceFlush().join(10, TimeUnit.SECONDS).isSuccess()).isTrue();

      assertThat(exporter.exported)
          .extracting(
              record -> record.getBodyValue() == null ? null : record.getBodyValue().asString())
          .containsExactly("A log for Grafana Cloud");
    } finally {
      logger.detachAppender(appender);
      appender.stop();
      sdk.shutdown().join(10, TimeUnit.SECONDS);
    }
  }
}
