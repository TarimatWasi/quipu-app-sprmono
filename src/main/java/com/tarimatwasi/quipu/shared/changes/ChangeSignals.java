package com.tarimatwasi.quipu.shared.changes;

import org.jspecify.annotations.Nullable;

/**
 * TAR-371/372: transactional invalidations; delivery is best-effort, never a business event bus.
 */
public interface ChangeSignals {
  /** The resource groups affected by the confirmed operation. */
  enum Kind {
    PAYMENT,
    FINANCIAL,
    CONTRACT,
    GUEST
  }

  /** Internal references only; ids are masked separately for every receiving session. */
  record Change(
      Kind kind,
      Long guestId,
      boolean contractType,
      @Nullable Long paymentId,
      @Nullable Long environmentId) {}

  /** Register a resource change in the current transaction; rollback publishes nothing. */
  void changed(Change change);

  /** Close the streams of one revoked session after commit. */
  void revokeSession(Long userId, String sidHash);

  /** Close older credentials; the kept session may renew rather than log out. */
  void credentialsChanged(Long userId, long newVersion, @Nullable String keptSidHash);

  /** Close current streams of a locked account after commit. */
  void accountLocked(Long userId);
}
