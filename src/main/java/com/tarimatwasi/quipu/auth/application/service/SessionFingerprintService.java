package com.tarimatwasi.quipu.auth.application.service;

import com.tarimatwasi.quipu.auth.application.port.in.SessionFingerprintUseCase;
import com.tarimatwasi.quipu.auth.application.port.out.SessionStorePort;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read inside the transaction that closed the session (logout, refresh, password change), often
 * before any user is authenticated: no role applies, and it returns only a one-way hash.
 */
@Service
public class SessionFingerprintService implements SessionFingerprintUseCase {
  private final SessionStorePort sessions;

  SessionFingerprintService(SessionStorePort sessions) {
    this.sessions = sessions;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<String> fingerprintOf(Long sessionId) {
    return sessions.sidHashOf(sessionId);
  }
}
