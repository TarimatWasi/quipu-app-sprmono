package com.tarimatwasi.quipu.auth.port.out;

import com.tarimatwasi.quipu.shared.access.AccessMode;
import java.time.Instant;
import java.util.Optional;

/** The accesses of the guests; only the SHA-256 of each secret is stored (RN-32). */
public interface AccessGrantRepositoryPort {

  /** Revokes the access of the guest that is neither used nor revoked, if there is one. */
  void revokeLive(Long guestId, Instant now);

  /** Stores a new access; the live one must have been revoked first. */
  void save(Long guestId, AccessMode mode, String tokenHash, Instant expiresAt);

  /**
   * Marks the link with that hash as used, only if it is unused, not revoked and not expired at
   * {@code now}. Atomic: of two simultaneous calls with one hash only one gets the guest.
   *
   * @return the guest of the link, or empty if no link was consumed
   */
  Optional<Long> consumeLink(String tokenHash, Instant now);

  /** The temporary password of the guest that is neither used nor revoked, expired or not. */
  Optional<LiveTemporaryPassword> findLiveTemporaryPassword(Long guestId);

  /** Marks the live temporary password of the guest as used (D-30), if there is one. */
  void consumeTemporaryPassword(Long guestId, Instant now);

  /**
   * When the access of the guest that is unused, not revoked and not expired at {@code now} ends.
   */
  Optional<Instant> liveExpiry(Long guestId, Instant now);

  /** The hash of a temporary password and when it expires. */
  record LiveTemporaryPassword(String tokenHash, Instant expiresAt) {}
}
