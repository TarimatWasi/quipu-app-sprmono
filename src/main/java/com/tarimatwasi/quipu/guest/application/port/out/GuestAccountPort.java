package com.tarimatwasi.quipu.guest.application.port.out;

import com.tarimatwasi.quipu.shared.access.AccessMode;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** What the guest module needs to know about the access account that belongs to a guest. */
public interface GuestAccountPort {

  /**
   * What the account of each guest says. A guest without an account is not in the map, which is the
   * same as an account that cannot log in and has not chosen a password.
   */
  Map<Long, AccountState> stateOf(Collection<Long> guestIds);

  /** Enables or disables the account of the guest, if it has one (RN-12). */
  void setActive(Long guestId, boolean active);

  /**
   * Whether an administrator account owns that document, so no guest can be registered under it.
   */
  boolean documentBelongsToAdministrator(String documentType, String documentNumber);

  /**
   * Creates the account of the guest if it has none (RF-11). Joins the caller's transaction.
   *
   * @throws GuestDocumentAlreadyExistsException if another account has the document
   */
  void openAccount(
      Long guestId, String documentType, String documentNumber, @Nullable String email);

  /**
   * TAR-229: creates the account of a guest just registered with that email as its account email;
   * an email another account has is refused, not dropped (D-77). Joins the caller's transaction.
   *
   * @throws GuestDocumentAlreadyExistsException if another account has the document
   * @throws AccountEmailAlreadyExistsException if another account has the email, ignoring case
   */
  void openNewAccount(
      Long guestId, String documentType, String documentNumber, @Nullable String email);

  /**
   * TAR-229: sets or changes the account email of the guest; a change removes the pending recovery
   * code. Joins the caller's transaction, which holds the lock of the guest.
   *
   * @throws AccountEmailAlreadyExistsException if another account has the email, ignoring case
   */
  AccountEmailChange changeAccountEmail(Long guestId, String email);

  /** Tells the previous account email about the change, best-effort. After the commit. */
  void noticeAccountEmailChanged(String previousEmail, String newEmail);

  /** Revokes the live access of the guest and issues a new one (RN-32). Joins the transaction. */
  IssuedAccess issueAccess(Long guestId, AccessMode mode);

  /** Queues the email with the access, best-effort; whether it was queued. After the commit. */
  boolean sendAccess(String email, IssuedAccess access);

  /**
   * The account of the guest logs in with its corrected document (RN-36). Joins the transaction.
   *
   * @throws GuestDocumentAlreadyExistsException if another account has that document
   */
  void changeAccountDocument(Long guestId, String documentType, String documentNumber);

  /** Revokes the live access of the guest, if any (RN-12). Joins the caller's transaction. */
  void revokeAccess(Long guestId);

  /** When the live, unused access of the guest expires; empty if it has none. */
  Optional<Instant> accessExpiry(Long guestId);

  /** An access just issued; {@code secret} is the activation URL or the temporary password. */
  record IssuedAccess(AccessMode mode, String secret, Instant expiresAt, String shareMessage) {
    @Override
    public String toString() {
      // Never the secret: this record may reach a log by accident.
      return "IssuedAccess[mode=" + mode + ", expiresAt=" + expiresAt + "]";
    }
  }

  /** What a change of account email did. */
  enum EmailChangeOutcome {
    NO_ACCOUNT,
    UNCHANGED,
    CHANGED
  }

  /**
   * A change of account email; {@code previousEmail} is the email replaced by a change (null if the
   * account had none or nothing changed).
   */
  record AccountEmailChange(EmailChangeOutcome outcome, @Nullable String previousEmail) {
    @Override
    public String toString() {
      // Never the email: this record may reach a log by accident.
      return "AccountEmailChange[outcome=" + outcome + "]";
    }
  }

  /**
   * The state of one guest's account.
   *
   * @param canLogIn the account is not disabled and has a usable credential (its own password or a
   *     live temporary one)
   * @param passwordChosen the guest chose its own password (no temporary one is pending)
   */
  record AccountState(boolean canLogIn, boolean passwordChosen) {}
}
