package com.tarimatwasi.quipu.auth.port.out;

import com.tarimatwasi.quipu.shared.access.AccessMode;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;

/** The accesses of the guests; only the SHA-256 of each secret is stored (RN-32). */
public interface AccessGrantRepositoryPort {

  /** Revokes the access of the guest that is neither used nor revoked, if there is one. */
  void revokeLive(Long guestId, Instant now);

  /** Stores a new access; the live one must have been revoked first. */
  void save(Long guestId, AccessMode mode, String tokenHash, Instant expiresAt);

  /** The guest of the access with that hash, whatever its state; read without a lock. */
  Optional<Long> findGuestIdByTokenHash(String tokenHash);

  /**
   * Marks the link with that hash as used, only if it is unused, not revoked and not expired at
   * {@code now}. Atomic: of two simultaneous calls with one hash only one succeeds.
   *
   * @return whether this call consumed the link
   */
  boolean consumeLink(String tokenHash, Instant now);

  /** The temporary password of the guest that is neither used nor revoked, expired or not. */
  Optional<LiveTemporaryPassword> findLiveTemporaryPassword(Long guestId);

  /**
   * Whether that exact access is a temporary password of that guest that is unused, not revoked and
   * not expired at {@code now}: what a session opened with it needs to choose the password (RF-12).
   */
  boolean isLiveTemporaryPassword(Long grantId, Long guestId, Instant now);

  /**
   * Of those guests, the ones with a temporary password that is unused, not revoked and not expired
   * at {@code now}; one query for the whole page.
   */
  Set<Long> guestsWithLiveTemporaryPassword(Collection<Long> guestIds, Instant now);

  /**
   * Whether the guest has a link or temporary password with that hash that is unused, not revoked
   * and not expired at {@code now}: a secret someone else may still hold (TAR-36).
   */
  boolean isLiveAccess(Long guestId, String tokenHash, Instant now);

  /** Marks the live temporary password of the guest as used (D-30), if there is one. */
  void consumeTemporaryPassword(Long guestId, Instant now);

  /**
   * When the access of the guest that is unused, not revoked and not expired at {@code now} ends.
   */
  Optional<Instant> liveExpiry(Long guestId, Instant now);

  /** The hash of a temporary password and when it expires. */
  record LiveTemporaryPassword(Long id, String tokenHash, Instant expiresAt) {}
}
