package com.tarimatwasi.quipu.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Creations (RF-22), deactivations and reactivations (TAR-127) of administrators per session user:
 * the routes change privileged accounts and re-check a password, so one session cannot fire them
 * without pause.
 *
 * @param administratorCreationsPerMinute {@code app.rate-limit.administrator-creations-per-minute}:
 *     requests per minute and session user to create an administrator, as a burst refilled over the
 *     minute (3; from 1 to 60, so that a variable of the environment cannot loosen it without
 *     bound). Independent of the other limits.
 * @param administratorStatusChangesPerMinute {@code
 *     app.rate-limit.administrator-status-changes-per-minute}: the same for each of deactivating
 *     and reactivating an administrator, each with its own counter (3; from 1 to 60).
 */
@ConfigurationProperties("app.rate-limit")
@Validated
public record AdministratorRateLimitProperties(
    @DefaultValue("3") int administratorCreationsPerMinute,
    @DefaultValue("3") int administratorStatusChangesPerMinute) {

  /** The most that the configuration may allow. */
  static final int MAX_PER_MINUTE = 60;

  public AdministratorRateLimitProperties {
    requireWithinBounds(
        administratorCreationsPerMinute, "app.rate-limit.administrator-creations-per-minute");
    requireWithinBounds(
        administratorStatusChangesPerMinute,
        "app.rate-limit.administrator-status-changes-per-minute");
  }

  private static void requireWithinBounds(int perMinute, String property) {
    if (perMinute < 1 || perMinute > MAX_PER_MINUTE) {
      throw new IllegalArgumentException(property + " must be from 1 to " + MAX_PER_MINUTE);
    }
  }
}
