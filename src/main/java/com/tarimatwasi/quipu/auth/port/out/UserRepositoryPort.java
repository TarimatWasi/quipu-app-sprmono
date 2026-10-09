package com.tarimatwasi.quipu.auth.port.out;

import com.tarimatwasi.quipu.auth.domain.DocumentType;
import com.tarimatwasi.quipu.auth.domain.UserAccount;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserRepositoryPort {
  Optional<UserAccount> findByDocument(DocumentType documentType, String documentNumber);

  Optional<UserAccount> findById(Long id);

  /**
   * The accounts that belong to the guests with those ids; the ids without an account add nothing.
   */
  List<UserAccount> findAllByGuestIds(Collection<Long> guestIds);

  /**
   * Like {@link #findById} but the row stays locked until the transaction ends, so a change of
   * password and a login of the same account are decided one by one (TAR-125). Needs a transaction.
   */
  Optional<UserAccount> findByIdForUpdate(Long id);

  /**
   * Like {@link #findByDocument} but the account's row stays locked until the transaction ends, so
   * simultaneous logins of one account are decided one by one (SEG-06). Needs a transaction.
   */
  Optional<UserAccount> findByDocumentForUpdate(DocumentType documentType, String documentNumber);

  /**
   * Stores the new hash and clears the pending-change flag of the account. {@code changedAt} is
   * when the password changed: tokens issued before it stop being valid (TAR-125).
   */
  void changePassword(Long id, String newPasswordHash, Instant changedAt);

  /** Emails are unique ignoring case. */
  Optional<UserAccount> findByEmail(String email);

  /** When the account's current recovery code expires; empty if it has none. */
  Optional<Instant> findResetTokenExpiry(Long id);

  /** Replaces the account's recovery code by the hash of a new one (only one is valid). */
  void saveResetToken(Long id, String tokenHash, Instant expiresAt);

  Optional<PendingReset> findPendingReset(String tokenHash);

  /**
   * Stores the new hash, clears the pending-change flag and removes the recovery code, so the code
   * cannot be used again.
   */
  void resetPassword(Long id, String newPasswordHash, Instant changedAt);

  /**
   * SEG-06. Counts a failed login of the account and, when it reaches {@code maxAttempts}, locks it
   * until {@code now + lockDuration}. A lock that already expired restarts the count. Safe against
   * simultaneous failures: the account's row is locked while it is updated.
   *
   * @return true if the account was already locked when its row was locked: the attempt is not
   *     counted and the lock is not extended
   */
  boolean registerFailedLogin(Long id, Instant now, int maxAttempts, Duration lockDuration);

  /** Forgets the failed logins and the lock of the account (a successful login). */
  void clearFailedLogins(Long id);

  /** The account a recovery code belongs to and when the code expires. */
  record PendingReset(UserAccount account, Instant expiresAt) {}
}
