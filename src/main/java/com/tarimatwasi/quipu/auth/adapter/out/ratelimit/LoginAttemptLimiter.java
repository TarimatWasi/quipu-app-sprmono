package com.tarimatwasi.quipu.auth.adapter.out.ratelimit;

import com.tarimatwasi.quipu.auth.port.out.LoginAttemptLimiterPort;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The counters live in memory, which is right for the single instance of the free plan. The table
 * keeps the most recent accounts only, so it cannot grow without bound: an attacker naming
 * thousands of documents can make an account start over, which only means a fresh allowance.
 */
@Component
public class LoginAttemptLimiter implements LoginAttemptLimiterPort {

  private static final int MAX_TRACKED_ACCOUNTS = 10_000;

  private final int perMinute;
  private final Map<String, Bucket> buckets =
      Collections.synchronizedMap(
          new LinkedHashMap<>(16, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
              return size() > MAX_TRACKED_ACCOUNTS;
            }
          });

  public LoginAttemptLimiter(AccountRateLimitProperties properties) {
    this.perMinute = properties.perAccountPerMinute();
  }

  @Override
  public long tryAcquire(String documentType, String documentNumber) {
    // Spaces and letter case must not choose another bucket for the same document.
    String key = documentType + ":" + documentNumber.strip().toUpperCase(Locale.ROOT);
    ConsumptionProbe probe =
        buckets.computeIfAbsent(key, k -> newBucket()).tryConsumeAndReturnRemaining(1);
    if (probe.isConsumed()) {
      return 0;
    }
    return Math.max(1, Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds() + 1);
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
