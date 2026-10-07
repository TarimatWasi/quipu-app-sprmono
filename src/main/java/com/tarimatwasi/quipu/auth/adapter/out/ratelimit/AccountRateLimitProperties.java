package com.tarimatwasi.quipu.auth.adapter.out.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Login attempts per account (SEC-01, TAR-124).
 *
 * @param perAccountPerMinute {@code app.rate-limit.per-account-per-minute}: attempts per minute on
 *     one document, whatever the address (10)
 */
@ConfigurationProperties("app.rate-limit")
@Validated
public record AccountRateLimitProperties(@DefaultValue("10") int perAccountPerMinute) {

  public AccountRateLimitProperties {
    if (perAccountPerMinute < 1) {
      throw new IllegalArgumentException(
          "app.rate-limit.per-account-per-minute must be at least 1");
    }
  }
}
