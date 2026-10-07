package com.tarimatwasi.quipu.auth.adapter.out.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** TAR-124: login attempts are counted per account, whatever address they come from. */
class LoginAttemptLimiterTest {

  private static LoginAttemptLimiter limiter(int perMinute) {
    return new LoginAttemptLimiter(new AccountRateLimitProperties(perMinute));
  }

  @Test
  void letsTheAllowanceThroughAndThenSaysHowLongToWait() {
    var limiter = limiter(3);

    for (int i = 0; i < 3; i++) {
      assertThat(limiter.tryAcquire("DNI", "12345678")).isZero();
    }

    assertThat(limiter.tryAcquire("DNI", "12345678")).isBetween(1L, 61L);
  }

  @Test
  void countsEachAccountSeparately() {
    var limiter = limiter(1);

    assertThat(limiter.tryAcquire("DNI", "12345678")).isZero();
    assertThat(limiter.tryAcquire("DNI", "12345678")).isPositive();

    assertThat(limiter.tryAcquire("DNI", "87654321")).isZero();
  }

  @Test
  void theSameNumberOfAnotherDocumentTypeIsAnotherAccount() {
    var limiter = limiter(1);

    assertThat(limiter.tryAcquire("DNI", "12345678")).isZero();

    assertThat(limiter.tryAcquire("CE", "12345678")).isZero();
  }

  @Test
  void spacesAndLetterCaseDoNotChooseAnotherBucket() {
    var limiter = limiter(1);

    assertThat(limiter.tryAcquire("PASSPORT", "ab123456")).isZero();

    assertThat(limiter.tryAcquire("PASSPORT", "  AB123456 ")).isPositive();
  }

  @Test
  void aCrowdOfDistinctAccountsOnlyMakesTheOldestStartOver() {
    var limiter = limiter(1);
    assertThat(limiter.tryAcquire("DNI", "00000000")).isZero();

    for (int i = 1; i <= 10_001; i++) {
      limiter.tryAcquire("DNI", String.valueOf(i));
    }

    // The first account was forgotten to keep the table bounded: it has a fresh allowance.
    assertThat(limiter.tryAcquire("DNI", "00000000")).isZero();
  }

  @Test
  void theAllowanceMustBeAtLeastOne() {
    assertThatThrownBy(() -> new AccountRateLimitProperties(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("per-account-per-minute");
  }
}
