package com.tarimatwasi.quipu.auth.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class SessionJpaEntityTest {

  /** An entity that reaches a log by accident carries no hash of a credential. */
  @Test
  void itsTextNeverCarriesTheHash() {
    assertThat(
            new SessionJpaEntity(7L, "sid-hash-secret", 0, false, Instant.EPOCH, Instant.EPOCH)
                .toString())
        .contains("userId=7")
        .doesNotContain("sid-hash-secret");
  }
}
