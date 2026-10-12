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
 * @param originSecret {@code app.rate-limit.origin-secret} (variable {@code ORIGIN_SECRET}): the
 *     value that Cloudflare adds in {@code X-Origin-Secret} to every request (TAR-180). The client
 *     address header is trusted only when the request carries it; empty (local) never trusts it.
 */
@ConfigurationProperties("app.rate-limit")
@Validated
public record RateLimitProperties(
    @DefaultValue("60") int perMinute,
    @DefaultValue("") String clientIpHeader,
    @DefaultValue("") String originSecret) {

  public RateLimitProperties {
    if (perMinute < 1) {
      throw new IllegalArgumentException("app.rate-limit.per-minute must be at least 1");
    }
    // An unresolved ${ORIGIN_SECRET} stays as literal text while binding: refuse it so the
    // startup fails instead of running with a secret that anybody can read here.
    if (originSecret.startsWith("${")) {
      throw new IllegalArgumentException("app.rate-limit.origin-secret is not resolved");
    }
    // An empty value (ORIGIN_SECRET defined but blank) resolves fine too, and then no request
    // could ever present it: the address header would never be trusted and every client would
    // share the connection address. Local sets neither value and is not affected.
    if (!clientIpHeader.isBlank() && originSecret.isBlank()) {
      throw new IllegalArgumentException(
          "app.rate-limit.origin-secret is required when app.rate-limit.client-ip-header is set");
    }
  }
}
