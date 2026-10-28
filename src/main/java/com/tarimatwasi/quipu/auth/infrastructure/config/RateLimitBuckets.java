package com.tarimatwasi.quipu.auth.infrastructure.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/**
 * The in-memory counters of a rate limit filter: one bucket per key, of {@code burst} requests
 * refilled at {@code perMinute} per minute, and the 429 {@code RATE_LIMITED} answer with {@code
 * Retry-After}.
 *
 * <p>The counters live in memory, which is right for the single instance of the free plan. The
 * table keeps the most recent keys only, so it cannot grow without bound: whoever rotates thousands
 * of keys can make another one start over, which only means a fresh allowance.
 */
final class RateLimitBuckets {

  private static final int MAX_TRACKED_KEYS = 10_000;
  private static final String BODY =
      "{\"code\":\"RATE_LIMITED\",\"message\":\"Demasiados intentos, espera un momento antes de"
          + " volver a intentar\"}";

  private final int perMinute;
  private final int burst;
  private final Map<String, Bucket> buckets =
      Collections.synchronizedMap(
          new LinkedHashMap<>(16, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
              return size() > MAX_TRACKED_KEYS;
            }
          });

  /** A burst as large as the allowance of the minute. */
  RateLimitBuckets(int perMinute) {
    this(perMinute, perMinute);
  }

  /** Up to {@code burst} requests at once, refilled at {@code perMinute} per minute. */
  RateLimitBuckets(int perMinute, int burst) {
    this.perMinute = perMinute;
    this.burst = burst;
  }

  /**
   * Takes one request from the allowance of the key. Without allowance left it writes the 429 and
   * answers false: the caller must stop there.
   */
  boolean tryConsume(String key, HttpServletResponse response) throws IOException {
    Bucket bucket = buckets.computeIfAbsent(key, k -> newBucket());
    ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
    if (probe.isConsumed()) {
      return true;
    }
    reject(
        response, Math.max(1, Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds() + 1));
    return false;
  }

  /** Writes the 429 {@code RATE_LIMITED} answer. */
  static void reject(HttpServletResponse response, long retryAfterSeconds) throws IOException {
    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(BODY);
  }

  private Bucket newBucket() {
    return Bucket.builder()
        .addLimit(
            Bandwidth.builder()
                .capacity(burst)
                .refillGreedy(perMinute, Duration.ofMinutes(1))
                .build())
        .build();
  }
}
