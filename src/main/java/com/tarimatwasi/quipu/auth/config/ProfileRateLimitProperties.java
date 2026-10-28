package com.tarimatwasi.quipu.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Edits of the own profile per session user (CU-20, CU-23): the routes write personal data and
 * probe whether a contact email is taken, so one session cannot fire them without pause.
 *
 * @param profileUpdatesPerMinute {@code app.rate-limit.profile-updates-per-minute}: requests per
 *     minute and session user to PATCH /bff/guest/profile and /bff/temporary-guest/profile, one
 *     counter for both, as a burst refilled over the minute (10; from 1 to 60, so that a variable
 *     of the environment cannot loosen it without bound).
 */
@ConfigurationProperties("app.rate-limit")
@Validated
public record ProfileRateLimitProperties(@DefaultValue("10") int profileUpdatesPerMinute) {

  /** The most that the configuration may allow. */
  static final int MAX_PER_MINUTE = 60;

  public ProfileRateLimitProperties {
    if (profileUpdatesPerMinute < 1 || profileUpdatesPerMinute > MAX_PER_MINUTE) {
      throw new IllegalArgumentException(
          "app.rate-limit.profile-updates-per-minute must be from 1 to " + MAX_PER_MINUTE);
    }
  }
}
