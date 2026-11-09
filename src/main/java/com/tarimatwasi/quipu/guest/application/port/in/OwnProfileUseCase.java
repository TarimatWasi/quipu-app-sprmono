package com.tarimatwasi.quipu.guest.application.port.in;

import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.EmergencyContactView;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.GuestKind;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.GuestSummaryView;
import com.tarimatwasi.quipu.guest.application.port.in.OnboardingUseCase.DocumentUpload;
import com.tarimatwasi.quipu.guest.application.port.in.OnboardingUseCase.DocumentView;
import java.util.List;
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
      @Nullable EmergencyContactView emergencyContact) {

    /** Whether no field is present. */
    public boolean empty() {
      return name == null && phone == null && emergencyContact == null;
    }
  }

  /**
   * What «Mis datos» shows (H5, T3; TAR-242): the personal and contact data, the email and the
   * documents, never their files.
   *
   * @param email the one email of the guest, the one of its account, where the password recovery
   *     goes; the guest changes it only through its verification (TAR-349)
   * @param documentsLimit how many documents a guest may have (RN-37)
   */
  record OwnProfileView(
      String documentType,
      String documentNumber,
      String name,
      @Nullable String phone,
      @Nullable String email,
      @Nullable EmergencyContactView emergencyContact,
      List<DocumentView> documents,
      int documentsLimit) {}

  /**
   * The profile of the guest of the session.
   *
   * @param area the type of guest the route belongs to
   * @throws GuestNotFoundException if there is no such guest
   * @throws OwnProfileRefusedException if the guest is of the other type or still onboarding
   */
  OwnProfileView own(Long guestId, GuestKind area);

  /**
   * Stores one more document of the guest of the session after the onboarding (RN-37, TAR-242),
   * with the rules of the onboarding upload: JPG, PNG or PDF of up to 5 MB, at most 5 in total.
   *
   * @param area the type of guest the route belongs to
   * @throws InvalidOnboardingInputException if the file is not a JPG, PNG or PDF of up to 5 MB
   * @throws GuestNotFoundException if there is no such guest
   * @throws OwnProfileRefusedException if the guest is of the other type or still onboarding
   * @throws OnboardingConflictException with DOCUMENT_LIMIT_REACHED if it has 5 documents already
   * @throws GuestDocumentUnavailableException if the file store failed; nothing was saved
   */
  DocumentView uploadDocument(Long guestId, GuestKind area, DocumentUpload upload);

  /**
   * Applies the present fields to the guest of the session, with its row locked.
   *
   * @param area the type of guest the route belongs to
   * @throws EmptyGuestUpdateException if no field is present
   * @throws GuestNotFoundException if there is no such guest
   * @throws OwnProfileRefusedException if the guest is of the other type or still onboarding
   */
  GuestSummaryView update(Long guestId, GuestKind area, ProfileChange change);
}
