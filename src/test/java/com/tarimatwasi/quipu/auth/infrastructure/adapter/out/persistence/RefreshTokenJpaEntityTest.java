package com.tarimatwasi.quipu.auth.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class RefreshTokenJpaEntityTest {

  /** An entity that reaches a log by accident carries no hash of a credential. */
  @Test
  void itsTextNeverCarriesTheHash() {
    assertThat(new RefreshTokenJpaEntity(9L, "token-hash-secret", Instant.EPOCH).toString())
        .contains("sessionId=9")
        .doesNotContain("token-hash-secret");
  }
}
