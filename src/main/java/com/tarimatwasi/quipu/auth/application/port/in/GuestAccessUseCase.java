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
   * Creates the account of a guest just registered, with that email as its account email (RF-02,
   * D-77): unlike {@link #openAccount}, an email another account has is refused instead of being
   * dropped. Joins the transaction of the caller, which rolls the registration back.
   *
   * @throws AccountDocumentTakenException if another account has the document
   * @throws AccountEmailTakenException if another account has the email, ignoring case
   */
  void openNewAccount(
      Long guestId, String documentType, String documentNumber, @Nullable String email);

  /**
   * TAR-229 (RF-16, D-76): sets or changes the account email of the guest, the one the password
   * recovery writes to. A change removes the pending recovery code of the account. Joins the
   * transaction of the caller, which holds the lock of the guest; the account row stays locked
   * until the commit.
   *
   * @throws AccountEmailTakenException if another account has the email, ignoring case
   */
  AccountEmailChange changeAccountEmail(Long guestId, String email);

  /**
   * Tells the previous account email that it was replaced, best-effort (a failure is only logged);
   * call it after the commit.
   */
  void noticeAccountEmailChanged(String previousEmail, String newEmail);

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

  /** What {@link #changeAccountEmail} did. */
  enum EmailChangeOutcome {
    /** The guest has no account: nothing was written. */
    NO_ACCOUNT,
    /** The account already had that email, ignoring case: nothing was written. */
    UNCHANGED,
    /** The email was written and the pending recovery code removed. */
    CHANGED
  }

  /**
   * The result of a change of account email.
   *
   * @param previousEmail the email the account had before a change; null if it had none or nothing
   *     changed
   */
  record AccountEmailChange(EmailChangeOutcome outcome, @Nullable String previousEmail) {
    @Override
    public String toString() {
      // Never the email: this record may reach a log by accident.
      return "AccountEmailChange[outcome=" + outcome + "]";
    }
  }

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
