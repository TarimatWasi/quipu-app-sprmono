package com.tarimatwasi.quipu.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Uploads per session user (TAR-206): the inspection of a file is costly, so one user cannot keep
 * the others waiting by sending file after file.
 *
 * @param uploadsPerMinute {@code app.rate-limit.uploads-per-minute}: uploads per minute and session
 *     user on the routes that take a file, whatever the address (10). Independent of the limit by
 *     address of the authentication endpoints ({@link RateLimitProperties}).
 */
@ConfigurationProperties("app.rate-limit")
@Validated
public record UploadRateLimitProperties(@DefaultValue("10") int uploadsPerMinute) {

  public UploadRateLimitProperties {
    if (uploadsPerMinute < 1) {
      throw new IllegalArgumentException("app.rate-limit.uploads-per-minute must be at least 1");
    }
  }
}
