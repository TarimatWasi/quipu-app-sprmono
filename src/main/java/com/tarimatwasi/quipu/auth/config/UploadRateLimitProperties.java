package com.tarimatwasi.quipu.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Uploads per session user (TAR-206): the inspection of a file is costly, so one user cannot keep
 * the others waiting by sending file after file. Besides these counts, a user has one upload in
 * progress at most ({@link UploadRateLimitFilter}).
 *
 * @param uploadsPerMinute {@code app.rate-limit.uploads-per-minute}: uploads per minute and session
 *     user on the routes that take a file, whatever the address (10; from 1 to 60, so that a
 *     variable of the environment cannot loosen it without bound). Independent of the limit by
 *     address of the authentication endpoints ({@link RateLimitProperties}).
 * @param uploadBurst {@code app.rate-limit.upload-burst}: uploads one user may send in a row before
 *     the refill of the minute paces them (3; from 1 to {@code uploadsPerMinute})
 */
@ConfigurationProperties("app.rate-limit")
@Validated
public record UploadRateLimitProperties(
    @DefaultValue("10") int uploadsPerMinute, @DefaultValue("3") int uploadBurst) {

  /** The most that the configuration may allow. */
  static final int MAX_UPLOADS_PER_MINUTE = 60;

  public UploadRateLimitProperties {
    if (uploadsPerMinute < 1 || uploadsPerMinute > MAX_UPLOADS_PER_MINUTE) {
      throw new IllegalArgumentException(
          "app.rate-limit.uploads-per-minute must be from 1 to " + MAX_UPLOADS_PER_MINUTE);
    }
    if (uploadBurst < 1 || uploadBurst > uploadsPerMinute) {
      throw new IllegalArgumentException(
          "app.rate-limit.upload-burst must be from 1 to app.rate-limit.uploads-per-minute");
    }
  }
}
