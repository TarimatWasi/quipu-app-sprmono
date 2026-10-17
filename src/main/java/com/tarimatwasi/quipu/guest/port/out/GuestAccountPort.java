package com.tarimatwasi.quipu.guest.port.out;

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

  /**
   * The state of one guest's account.
   *
   * @param canLogIn the account exists, has a password and is not disabled
   * @param passwordChosen the guest chose its own password (no temporary one is pending)
   */
  record AccountState(boolean canLogIn, boolean passwordChosen) {}
}
