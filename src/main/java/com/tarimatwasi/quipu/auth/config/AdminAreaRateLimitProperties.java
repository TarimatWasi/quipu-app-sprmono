package com.tarimatwasi.quipu.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Requests per session user to the ADMIN area (TAR-221): one session cannot fire the routes of
 * /bff/admin/** without pause, whichever they are.
 *
 * @param adminRequestsPerMinute {@code app.rate-limit.admin-requests-per-minute}: requests per
 *     minute and session user to /bff/admin/**, of any method, as a burst refilled over the minute
 *     (120; from 1 to 600, so that a variable of the environment cannot loosen it without bound).
 *     The specific limits of the uploads ({@link UploadRateLimitProperties}) and of the creations
 *     of administrators ({@link AdministratorRateLimitProperties}) still apply on their own: a
 *     request to those routes counts in both.
 */
@ConfigurationProperties("app.rate-limit")
@Validated
public record AdminAreaRateLimitProperties(@DefaultValue("120") int adminRequestsPerMinute) {

  /** The most that the configuration may allow. */
  static final int MAX_PER_MINUTE = 600;

  public AdminAreaRateLimitProperties {
    if (adminRequestsPerMinute < 1 || adminRequestsPerMinute > MAX_PER_MINUTE) {
      throw new IllegalArgumentException(
          "app.rate-limit.admin-requests-per-minute must be from 1 to " + MAX_PER_MINUTE);
    }
  }
}
