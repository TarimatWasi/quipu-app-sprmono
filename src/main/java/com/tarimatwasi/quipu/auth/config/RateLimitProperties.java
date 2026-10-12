package com.tarimatwasi.quipu.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Rate limit of the sensitive endpoints (SEC-01: login and password recovery).
 *
 * @param perMinute {@code app.rate-limit.per-minute}: requests per minute and client address (60:
 *     generous on purpose, because behind Vercel every browser shares one address; the login also
 *     counts per account, see {@code AccountRateLimitProperties})
 * @param clientIpHeader {@code app.rate-limit.client-ip-header}: the header with the address of the
 *     real client, when the hosting layer in front of the backend hides it (empty: the address of
 *     the connection). Only set it for a header that the layer in front overwrites, never for one
 *     the client can send: it would let anyone choose its own bucket.
 */
@ConfigurationProperties("app.rate-limit")
@Validated
public record RateLimitProperties(
    @DefaultValue("60") int perMinute, @DefaultValue("") String clientIpHeader) {

  public RateLimitProperties {
    if (perMinute < 1) {
      throw new IllegalArgumentException("app.rate-limit.per-minute must be at least 1");
    }
  }
}
