package com.tarimatwasi.quipu.auth.port.in;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** TAR-131: the lock reports when it ends and the whole seconds left, rounded up. */
class AccountLockedExceptionTest {

  private static final Instant NOW = Instant.parse("2026-10-07T20:00:00Z");

  @Test
  void keepsTheInstantTheLockEnds() {
    var lockedUntil = NOW.plusSeconds(900);

    assertThat(new AccountLockedException(NOW, lockedUntil).lockedUntil()).isEqualTo(lockedUntil);
  }

  @ParameterizedTest
  @CsvSource({
    "900000, 900", // the full 15 minutes
    "899001, 900", // a fraction of a second left still counts as a second
    "1000, 1",
    "1, 1", // never 0: the client would retry at once and be refused again
    "0, 1", // already ended by the time the answer is built
    "-5000, 1"
  })
  void retryAfterIsTheMillisecondsLeftRoundedUpAndAtLeastOneSecond(
      long millisLeft, long expectedSeconds) {
    var exception = new AccountLockedException(NOW, NOW.plusMillis(millisLeft));

    assertThat(exception.retryAfterSeconds()).isEqualTo(expectedSeconds);
  }
}
