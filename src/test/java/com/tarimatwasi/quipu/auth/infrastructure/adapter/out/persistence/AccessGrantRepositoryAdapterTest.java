package com.tarimatwasi.quipu.auth.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AccessGrantRepositoryAdapterTest {

  private final AccessGrantJpaRepository repository = mock(AccessGrantJpaRepository.class);

  /** No guests, no question to the database. */
  @Test
  void noGuestsHaveNoLiveTemporaryPassword() {
    assertThat(
            new AccessGrantRepositoryAdapter(repository)
                .guestsWithLiveTemporaryPassword(List.of(), Instant.EPOCH))
        .isEmpty();
    verifyNoInteractions(repository);
  }
}
