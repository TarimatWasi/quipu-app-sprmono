package com.tarimatwasi.quipu.auth.application.port.in;

import java.util.Optional;

/**
 * The one-way fingerprint (SHA-256 of the sid) of a session, as the live streams know it: the
 * security events carry the internal session id, never the fingerprint.
 */
public interface SessionFingerprintUseCase {

  /** The fingerprint of the session; empty if there is no such session. */
  Optional<String> fingerprintOf(Long sessionId);
}
