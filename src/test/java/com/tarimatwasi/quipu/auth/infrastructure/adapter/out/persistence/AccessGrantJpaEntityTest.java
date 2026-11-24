package com.tarimatwasi.quipu.auth.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.shared.access.AccessMode;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AccessGrantJpaEntityTest {

  /** An entity that reaches a log by accident carries no hash of a credential. */
  @Test
  void itsTextNeverCarriesTheHash() {
    assertThat(
            new AccessGrantJpaEntity(5L, AccessMode.LINK, "grant-hash-secret", Instant.EPOCH)
                .toString())
        .contains("guestId=5")
        .doesNotContain("grant-hash-secret");
  }
}
