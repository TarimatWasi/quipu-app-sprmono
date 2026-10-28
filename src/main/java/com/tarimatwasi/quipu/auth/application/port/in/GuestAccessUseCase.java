package com.tarimatwasi.quipu.auth.application.port.in;

import com.tarimatwasi.quipu.shared.access.AccessMode;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * RF-11, RF-17: the account of a guest and the accesses (link or temporary password) the
 * administrator hands to it. The secrets exist in clear only in the answer of {@link #issueAccess}.
 */
public interface GuestAccessUseCase {

  /**
   * Creates the account of the guest if it has none: role GUEST and no usable password until the
   * guest uses an access. The email is kept only if no other account has it (the account email is
   * optional, D-33). Joins the transaction of the caller.
   */
  void openAccount(
      Long guestId, String documentType, String documentNumber, @Nullable String email);

  /**
   * Revokes the live access of the guest and issues a new one, valid 30 minutes (RN-32). The
   * current password of the account keeps working until the new access is used. Joins the
   * transaction of the caller.
   */
  IssuedAccess issueAccess(Long guestId, AccessMode mode);

  /**
   * Queues the email with the access, best-effort (RN-19); call it after the commit.
   *
   * @return whether the email was handed to the provider
   */
  boolean sendAccess(String email, IssuedAccess access);

  /**
   * Revokes the live access of the guest, if any (RN-12: a deactivated guest). Joins the caller.
   */
  void revokeAccess(Long guestId);

  /**
   * The account of the guest logs in with the document the administrator corrected (RN-36). Joins
   * the transaction of the caller, which holds the lock of the guest.
   *
   * @throws AccountDocumentTakenException if another account has that document
   */
  void changeAccountDocument(Long guestId, String documentType, String documentNumber);

  /** When the live, unused access of the guest expires; empty if it has none. */
  Optional<Instant> accessExpiry(Long guestId);

  /**
   * An access just issued.
   *
   * @param secret the activation URL (LINK) or the temporary password (TEMPORARY_PASSWORD)
   * @param shareMessage Spanish text with the secret and the 30-minute notice
   */
  record IssuedAccess(AccessMode mode, String secret, Instant expiresAt, String shareMessage) {
    @Override
    public String toString() {
      // Never the secret: this record may reach a log by accident.
      return "IssuedAccess[mode=" + mode + ", expiresAt=" + expiresAt + "]";
    }
  }
}
