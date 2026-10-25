package com.tarimatwasi.quipu.auth.config;

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
 * The in-memory counters of a rate limit filter: one bucket of {@code perMinute} requests per
 * minute and key, and the 429 {@code RATE_LIMITED} answer with {@code Retry-After}.
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
  private final Map<String, Bucket> buckets =
      Collections.synchronizedMap(
          new LinkedHashMap<>(16, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
              return size() > MAX_TRACKED_KEYS;
            }
          });

  RateLimitBuckets(int perMinute) {
    this.perMinute = perMinute;
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
    long retryAfterSeconds =
        Math.max(1, Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds() + 1);
    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(BODY);
    return false;
  }

  private Bucket newBucket() {
    return Bucket.builder()
        .addLimit(
            Bandwidth.builder()
                .capacity(perMinute)
                .refillGreedy(perMinute, Duration.ofMinutes(1))
                .build())
        .build();
  }
}
