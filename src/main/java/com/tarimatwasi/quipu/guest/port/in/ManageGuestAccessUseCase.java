package com.tarimatwasi.quipu.guest.port.in;

import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase.GuestKind;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase.GuestSummaryView;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase.IdDocumentKind;
import com.tarimatwasi.quipu.shared.access.AccessMode;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** RF-02, RF-11, RF-17: the administrator registers a guest and hands it an access. */
public interface ManageGuestAccessUseCase {

  /** The minimal registration (RN-31); phone and email are optional. */
  record RegisterCommand(
      IdDocumentKind documentType,
      String documentNumber,
      GuestKind type,
      @Nullable String phone,
      @Nullable String contactEmail,
      AccessMode accessMode) {}

  /**
   * An access in clear, shown only once (RN-32).
   *
   * @param secret the activation URL (LINK) or the temporary password (TEMPORARY_PASSWORD)
   * @param emailSent whether the access was queued to the guest's email (RN-19)
   */
  record AccessView(
      AccessMode mode, String secret, Instant expiresAt, boolean emailSent, String shareMessage) {
    @Override
    public String toString() {
      // Never the secret: this record may reach a log by accident.
      return "AccessView[mode=" + mode + ", expiresAt=" + expiresAt + "]";
    }
  }

  /**
   * The answer of a registration: {@code access} is null when the document was already registered
   * (RN-18), and then nothing was created or changed.
   */
  record Registration(GuestSummaryView guest, @Nullable AccessView access) {}

  /**
   * Registers the guest pending activation, with its account and its first access, and emails the
   * access if an email was given. A document already registered answers that guest, unchanged.
   *
   * @throws InvalidGuestDocumentException if the number does not fit the type
   * @throws GuestDocumentTakenException if an administrator account has the document
   * @throws GuestInactiveException if the document belongs to an inactive guest
   * @throws GuestEmailTakenException if another guest has the email
   */
  Registration register(RegisterCommand command);

  /**
   * Revokes the live access of the guest and issues a new one (RF-17); any guest that is not
   * inactive qualifies. The access is emailed if the guest has an email.
   *
   * @throws GuestNotFoundException if there is no guest with that id
   * @throws GuestInactiveException if the guest is inactive
   */
  AccessView generateAccess(Long guestId, AccessMode mode);
}
