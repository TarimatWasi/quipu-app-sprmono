package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.support;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Initial resource bounds of ADR-SSE-01; no deployment-specific defaults. */
@Validated
@ConfigurationProperties("app.changes")
public record ChangeStreamProperties(
    @Min(1) @Max(4) int perAccount,
    @Min(1) @Max(64) int total,
    @Min(1) @Max(64) int pendingKeys,
    @Min(256) @Max(8192) int frameBytes,
    @NotNull Duration heartbeat,
    @NotNull Duration maxDuration) {
  public ChangeStreamProperties {
    if (heartbeat != null
        && (heartbeat.isZero()
            || heartbeat.isNegative()
            || heartbeat.compareTo(Duration.ofSeconds(20)) > 0)) {
      throw new IllegalArgumentException("Stream heartbeat must be in (0,20s]");
    }
    if (maxDuration != null
        && (maxDuration.isZero()
            || maxDuration.isNegative()
            || maxDuration.compareTo(Duration.ofMinutes(15)) > 0)) {
      throw new IllegalArgumentException("Stream lifetime must be in (0,15m]");
    }
  }
}
