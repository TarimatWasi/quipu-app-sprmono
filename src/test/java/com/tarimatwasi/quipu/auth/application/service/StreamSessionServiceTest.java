package com.tarimatwasi.quipu.auth.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.auth.application.port.in.NoActiveSessionException;
import com.tarimatwasi.quipu.auth.application.port.out.SessionStorePort;
import com.tarimatwasi.quipu.auth.application.port.out.UserRepositoryPort;
import com.tarimatwasi.quipu.auth.domain.DocumentType;
import com.tarimatwasi.quipu.auth.domain.Role;
import com.tarimatwasi.quipu.auth.domain.UserAccount;
import com.tarimatwasi.quipu.support.MutableClock;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class StreamSessionServiceTest {
  private final UserRepositoryPort users = mock(UserRepositoryPort.class);
  private final SessionStorePort sessions = mock(SessionStorePort.class);
  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-10T12:00:00Z"));
  private final StreamSessionService service = new StreamSessionService(users, sessions, clock);

  private UserAccount account(
      Role role, @Nullable Long guest, boolean pending, String status, @Nullable Instant locked) {
    return new UserAccount(
        1L,
        null,
        DocumentType.DNI,
        "00000001",
        "hash",
        role,
        guest,
        pending,
        status,
        0,
        locked,
        null,
        2);
  }

  @Test
  void authorize_validRoles_returnsOnlyInternalIdentity() {
    when(users.findById(1L))
        .thenReturn(Optional.of(account(Role.ADMIN, null, false, "ACTIVE", null)));
    assertThat(service.authorize(1L, "hash", 2, true).administrator()).isTrue();
    when(users.findById(1L))
        .thenReturn(Optional.of(account(Role.GUEST, 9L, false, "ACTIVE", null)));
    assertThat(service.authorize(1L, "hash", 2, false).guestId()).isEqualTo(9L);
  }

  @Test
  void authorize_invalidSessionVersionOrRole_rejects() {
    when(users.findById(1L))
        .thenReturn(Optional.of(account(Role.ADMIN, null, false, "ACTIVE", null)));
    assertThatThrownBy(() -> service.authorize(1L, "hash", 1, true))
        .isInstanceOf(NoActiveSessionException.class);
    assertThatThrownBy(() -> service.authorize(1L, "hash", 2, false))
        .isInstanceOf(NoActiveSessionException.class);
    when(sessions.isClosed("hash", clock.instant())).thenReturn(true);
    assertThatThrownBy(() -> service.authorize(1L, "hash", 2, true))
        .isInstanceOf(NoActiveSessionException.class);
    when(sessions.isClosed("hash", clock.instant())).thenReturn(false);
    when(users.findById(1L))
        .thenReturn(Optional.of(account(Role.GUEST, null, false, "ACTIVE", null)));
    assertThatThrownBy(() -> service.authorize(1L, "hash", 2, false))
        .isInstanceOf(NoActiveSessionException.class);
  }

  @Test
  void authorize_ineligibleAccount_rejects() {
    when(users.findById(1L))
        .thenReturn(Optional.of(account(Role.ADMIN, null, false, "INACTIVE", null)));
    assertThatThrownBy(() -> service.authorize(1L, "hash", 2, true))
        .isInstanceOf(NoActiveSessionException.class);
    when(users.findById(1L))
        .thenReturn(Optional.of(account(Role.ADMIN, null, true, "ACTIVE", null)));
    assertThatThrownBy(() -> service.authorize(1L, "hash", 2, true))
        .isInstanceOf(NoActiveSessionException.class);
    when(users.findById(1L))
        .thenReturn(
            Optional.of(
                account(Role.ADMIN, null, false, "ACTIVE", clock.instant().plusSeconds(60))));
    assertThatThrownBy(() -> service.authorize(1L, "hash", 2, true))
        .isInstanceOf(NoActiveSessionException.class);
    when(users.findById(1L)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.authorize(1L, "hash", 2, true))
        .isInstanceOf(NoActiveSessionException.class);
  }
}
