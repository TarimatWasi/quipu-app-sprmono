package com.tarimatwasi.quipu.auth.application.service;

import com.tarimatwasi.quipu.auth.application.port.in.NoActiveSessionException;
import com.tarimatwasi.quipu.auth.application.port.in.StreamSessionUseCase;
import com.tarimatwasi.quipu.auth.application.port.out.SessionStorePort;
import com.tarimatwasi.quipu.auth.application.port.out.UserRepositoryPort;
import com.tarimatwasi.quipu.auth.domain.Role;
import java.time.Clock;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@PreAuthorize("hasAnyRole('ADMIN', 'GUEST')")
public class StreamSessionService implements StreamSessionUseCase {
  private final UserRepositoryPort users;
  private final SessionStorePort sessions;
  private final Clock clock;

  public StreamSessionService(UserRepositoryPort users, SessionStorePort sessions, Clock clock) {
    this.users = users;
    this.sessions = sessions;
    this.clock = clock;
  }

  @Override
  @Transactional(readOnly = true)
  public Identity authorize(
      Long userId, String sidHash, long securityVersion, boolean administrator) {
    var user = users.findById(userId).orElseThrow(NoActiveSessionException::new);
    if (user.isDisabled()
        || user.isLockedAt(clock.instant())
        || user.mustChangePassword()
        || user.securityVersion() != securityVersion
        || (user.role() == Role.ADMIN) != administrator
        || (!administrator && user.guestId() == null)
        || sessions.isClosed(sidHash, clock.instant())) {
      throw new NoActiveSessionException();
    }
    return new Identity(user.id(), user.guestId(), administrator);
  }
}
