package com.tarimatwasi.quipu.shared.changes;

import java.util.Set;
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
    GUEST,
    GUEST_RECORD,
    PROFILE,
    DOCUMENT,
    STAY,
    ENVIRONMENT,
    EXPENSE,
    FINE,
    DIRECTORY
  }

  /** Internal references only; ids are masked separately for every receiving session. */
  record Change(
      Kind kind,
      @Nullable Long guestId,
      boolean contractType,
      @Nullable Long paymentId,
      @Nullable Long environmentId,
      Set<Long> relatedGuests) {
    public Change {
      relatedGuests = Set.copyOf(relatedGuests);
    }

    public Change(
        Kind kind,
        @Nullable Long guestId,
        boolean contractType,
        @Nullable Long paymentId,
        @Nullable Long environmentId) {
      this(kind, guestId, contractType, paymentId, environmentId, Set.of());
    }
  }

  /** Register a resource change in the current transaction; rollback publishes nothing. */
  void changed(Change change);

  /** Close the streams of one revoked session after commit. */
  void revokeSession(Long userId, String sidHash);

  /** Close older credentials; the kept session may renew rather than log out. */
  void credentialsChanged(Long userId, long newVersion, @Nullable String keptSidHash);

  /** Close current streams of a locked account after commit. */
  void accountLocked(Long userId);
}
