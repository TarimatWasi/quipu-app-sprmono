package com.tarimatwasi.quipu.auth.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class UserJpaEntityTest {

  private static final Instant T0 = Instant.parse("2026-10-14T12:00:00Z");
  private static final Duration LOCK = Duration.ofMinutes(15);

  /** A lock that ran out starts the count again: the next failures lock it only at the limit. */
  @Test
  void anExpiredLockStartsTheCountAgain() {
    UserJpaEntity user = new UserJpaEntity();

    assertThat(user.registerFailedLogin(T0, 2, LOCK)).isFalse();
    assertThat(user.registerFailedLogin(T0, 2, LOCK)).isFalse(); // locks now
    assertThat(user.registerFailedLogin(T0.plusSeconds(1), 2, LOCK)).isTrue();

    Instant later = T0.plus(LOCK).plusSeconds(1);
    assertThat(user.registerFailedLogin(later, 2, LOCK)).isFalse();
    assertThat(user.registerFailedLogin(later, 2, LOCK)).isFalse(); // the limit again
    assertThat(user.registerFailedLogin(later.plusSeconds(1), 2, LOCK)).isTrue();
  }
}
