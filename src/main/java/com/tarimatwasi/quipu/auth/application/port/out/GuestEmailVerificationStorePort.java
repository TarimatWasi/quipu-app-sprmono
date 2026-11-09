package com.tarimatwasi.quipu.auth.application.port.out;

import java.time.Instant;
import java.util.Optional;

/** TAR-349: the pending email verification of a guest, at most one per guest. */
public interface GuestEmailVerificationStorePort {

  /** A pending verification: only the hash of its code is kept. */
  record PendingEmailVerification(
      String email, String codeHash, Instant expiresAt, Instant lastSentAt, int attempts) {}

  /** The pending verification of the guest, if any. */
  Optional<PendingEmailVerification> find(Long guestId);

  /** Stores the verification of the guest, replacing the one it had, with no failed attempts. */
  void replace(Long guestId, String email, String codeHash, Instant expiresAt, Instant sentAt);

  /** Counts one more wrong code. */
  void addFailedAttempt(Long guestId);

  /** Forgets the pending verification of the guest, if any. */
  void delete(Long guestId);
}
