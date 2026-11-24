package com.tarimatwasi.quipu.auth.application.service;

import com.tarimatwasi.quipu.auth.application.port.in.SessionRetentionUseCase;
import com.tarimatwasi.quipu.auth.application.port.out.SessionStorePort;
import java.time.Clock;
import java.time.Duration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TAR-228: a session and its refresh tokens are kept until a day after their absolute expiry, then
 * removed (until now they were kept forever, with a token for every renewal). That margin is what
 * makes the removal safe: a missing session counts as open ({@link SessionStorePort#isClosed}), and
 * no token can name it by then. An access token lives 15 minutes and is only issued while its
 * session is open; a temporary one expires with its session; a stream lasts 15 minutes. The rotated
 * tokens stay while their session lives, since one that comes back closes it (theft).
 */
@Service
public class SessionRetentionService implements SessionRetentionUseCase {

  /** Past the absolute expiry, longer than any token of the session can live. */
  static final Duration GRACE = Duration.ofDays(1);

  private final SessionStorePort sessions;
  private final Clock clock;

  SessionRetentionService(SessionStorePort sessions, Clock clock) {
    this.sessions = sessions;
    this.clock = clock;
  }

  @Override
  @Transactional
  public int purgeEnded() {
    return sessions.deleteExpiredBefore(clock.instant().minus(GRACE), BATCH);
  }
}
