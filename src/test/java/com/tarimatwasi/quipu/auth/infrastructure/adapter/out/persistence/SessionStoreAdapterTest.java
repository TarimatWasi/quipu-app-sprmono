package com.tarimatwasi.quipu.auth.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

class SessionStoreAdapterTest {

  private final SessionJpaRepository sessions = mock(SessionJpaRepository.class);
  private final RefreshTokenJpaRepository refreshTokens = mock(RefreshTokenJpaRepository.class);
  private final SessionStoreAdapter adapter =
      new SessionStoreAdapter(sessions, refreshTokens, mock(EntityManager.class));

  @Test
  void anUnknownRefreshTokenFindsNothingAndLocksNothing() {
    when(refreshTokens.findByTokenHash("hash")).thenReturn(Optional.empty());

    assertThat(adapter.findRefreshTokenForUpdate("hash")).isEmpty();
    verify(sessions, never()).findByIdForUpdate(ArgumentMatchers.anyLong());
  }

  /** The session went (the daily purge) between reading the token and locking it: nothing. */
  @Test
  void aTokenWhoseSessionWentMeanwhileFindsNothing() {
    when(refreshTokens.findByTokenHash("hash"))
        .thenReturn(Optional.of(new RefreshTokenJpaEntity(9L, "hash", Instant.EPOCH)));
    when(sessions.findByIdForUpdate(9L)).thenReturn(Optional.empty());

    assertThat(adapter.findRefreshTokenForUpdate("hash")).isEmpty();
  }
}
