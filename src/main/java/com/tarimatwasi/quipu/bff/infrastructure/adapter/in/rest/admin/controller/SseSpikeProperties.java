package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.admin.controller;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * TAR-365 spike, temporary: the stream of {@link SseSpikeBffController}. Removed with it.
 *
 * @param enabled {@code app.spike.sse.enabled}: off unless a profile turns it on (only dev)
 * @param interval {@code app.spike.sse.interval}: time between two ticks of the stream
 * @param maxDuration {@code app.spike.sse.max-duration}: the stream ends then at the latest, or
 *     earlier when the access token of the session expires
 */
@ConfigurationProperties("app.spike.sse")
@Validated
public record SseSpikeProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("20s") Duration interval,
    @DefaultValue("30m") Duration maxDuration) {

  /** A stream without pauses or without end is not a measurement: fail at startup. */
  public SseSpikeProperties {
    if (interval.isNegative() || interval.isZero()) {
      throw new IllegalArgumentException("app.spike.sse.interval must be positive");
    }
    if (maxDuration.compareTo(interval) < 0) {
      throw new IllegalArgumentException(
          "app.spike.sse.max-duration must be at least app.spike.sse.interval");
    }
  }
}
