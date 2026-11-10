package com.tarimatwasi.quipu.auth.application.service;

import java.time.Duration;

/**
 * TAR-228: how long a session lives. Decided by the person in charge on 2026-11-09.
 *
 * @param idle a refresh token unused this long stops renewing its session (7 days)
 * @param absolute no session outlives this from its login, whatever its refreshes (30 days)
 * @param reuseGrace a refresh token rotated less than this ago is a race of tabs, not a reuse (30
 *     seconds)
 */
public record SessionPolicy(Duration idle, Duration absolute, Duration reuseGrace) {

  public SessionPolicy {
    requirePositive(idle, "idle");
    requirePositive(absolute, "absolute");
    requirePositive(reuseGrace, "reuseGrace");
    if (idle.compareTo(absolute) > 0) {
      throw new IllegalArgumentException("The idle expiry cannot exceed the absolute one");
    }
  }

  private static void requirePositive(Duration value, String name) {
    if (value == null || value.isNegative() || value.isZero()) {
      throw new IllegalArgumentException(name + " must be positive, got: " + value);
    }
  }
}
