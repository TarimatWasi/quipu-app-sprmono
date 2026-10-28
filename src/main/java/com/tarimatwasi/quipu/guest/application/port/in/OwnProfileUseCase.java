package com.tarimatwasi.quipu.guest.application.port.in;

import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.EmergencyContactView;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.GuestKind;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.GuestSummaryView;
import org.jspecify.annotations.Nullable;

/**
 * CU-20, CU-23, RN-17: the guest edits its own personal and contact data once the onboarding is
 * complete. The document, the contract and the stays are not edited here. The caller passes the
 * guest of the session, never one named in the request.
 */
public interface OwnProfileUseCase {

  /**
   * What changes; a {@code null} field keeps its value. The strings are already within the limits
   * of the contract. The emergency contact, when present, replaces the stored one; one with no
   * field removes it.
   */
  record ProfileChange(
      @Nullable String name,
      @Nullable String phone,
      @Nullable String contactEmail,
      @Nullable EmergencyContactView emergencyContact) {

    /** Whether no field is present. */
    public boolean empty() {
      return name == null && phone == null && contactEmail == null && emergencyContact == null;
    }
  }

  /**
   * Applies the present fields to the guest of the session, with its row locked.
   *
   * @param area the type of guest the route belongs to
   * @throws EmptyGuestUpdateException if no field is present
   * @throws GuestNotFoundException if there is no such guest
   * @throws OwnProfileRefusedException if the guest is of the other type or still onboarding
   * @throws GuestEmailTakenException if another guest has the contact email
   */
  GuestSummaryView update(Long guestId, GuestKind area, ProfileChange change);
}
