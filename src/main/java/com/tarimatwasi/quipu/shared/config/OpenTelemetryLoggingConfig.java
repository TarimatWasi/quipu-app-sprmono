package com.tarimatwasi.quipu.shared.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Connects the {@code OpenTelemetryAppender} of {@code logback-spring.xml} to the SDK that Spring
 * Boot builds (ADR-F5). The appender is not part of Boot, so it needs this one call; the logs it
 * captured before the context was ready are sent then.
 */
@Configuration
public class OpenTelemetryLoggingConfig {

  @Bean
  public InitializingBean openTelemetryAppenderInstaller(OpenTelemetry openTelemetry) {
    return () -> OpenTelemetryAppender.install(openTelemetry);
  }
}
