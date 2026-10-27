package com.tarimatwasi.quipu.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Creations of administrators per session user (RF-22): the route creates privileged accounts and
 * re-checks a password, so one session cannot fire it without pause.
 *
 * @param administratorCreationsPerMinute {@code app.rate-limit.administrator-creations-per-minute}:
 *     requests per minute and session user to create an administrator, as a burst refilled over the
 *     minute (3; from 1 to 60, so that a variable of the environment cannot loosen it without
 *     bound). Independent of the other limits.
 */
@ConfigurationProperties("app.rate-limit")
@Validated
public record AdministratorRateLimitProperties(
    @DefaultValue("3") int administratorCreationsPerMinute) {

  /** The most that the configuration may allow. */
  static final int MAX_PER_MINUTE = 60;

  public AdministratorRateLimitProperties {
    if (administratorCreationsPerMinute < 1 || administratorCreationsPerMinute > MAX_PER_MINUTE) {
      throw new IllegalArgumentException(
          "app.rate-limit.administrator-creations-per-minute must be from 1 to " + MAX_PER_MINUTE);
    }
  }
}
