package com.tarimatwasi.quipu.guest.application.port.in;

import com.tarimatwasi.quipu.guest.application.port.in.GuestSessionUseCase.OnboardingStep;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.EmergencyContactView;
import com.tarimatwasi.quipu.guest.domain.GuestDocument;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The mandatory, resumable onboarding of a guest of either type (RF-14, RF-20, RN-33): personal
 * data, the optional documents (RN-37) and the consent (RN-14), in that order; the password was
 * chosen before. The caller passes the guest of the session, never one named in the request.
 */
public interface OnboardingUseCase {

  /** The largest document a guest may upload (RF-15). */
  int MAX_DOCUMENT_BYTES = GuestDocument.MAX_BYTES;

  /**
   * The personal data as the guest sees them.
   *
   * @param email the one email of the guest, the one of its account; it changes only through its
   *     verification (TAR-349), never with this step
   */
  record PersonalData(
      String name,
      String phone,
      @Nullable String email,
      @Nullable EmergencyContactView emergencyContact) {}

  /** The personal data step; the strings are already within the limits of the contract. */
  record PersonalDataInput(
      String name, String phone, @Nullable EmergencyContactView emergencyContact) {}

  /** A document as the guest sees it: never its file nor a link to it. */
  record DocumentView(
      Long id, String originalName, String contentType, long sizeBytes, Instant uploadedAt) {}

  /** A consent text: the one in force while onboarding, the accepted one afterwards. */
  record ConsentView(String version, String text, boolean accepted) {}

  /**
   * Where the guest is.
   *
   * @param nextStep the first pending step; null once the onboarding is complete
   * @param personalData known values, including defaults, even before the step is saved
   */
  record OnboardingView(
      boolean completed,
      @Nullable OnboardingStep nextStep,
      PersonalData personalData,
      List<DocumentView> documents,
      ConsentView consent) {}

  /** The uploaded file: its name as the browser sent it and its bytes. */
  final class DocumentUpload {

    private final String originalName;
    private final byte[] content;

    /** The file with the name the browser gave it. */
    public DocumentUpload(String originalName, byte[] content) {
      this.originalName = originalName;
      this.content = content;
    }

    /** The name as the browser sent it; it is sanitized before it is stored. */
    public String originalName() {
      return originalName;
    }

    /** The bytes of the file; the caller must not change them. */
    public byte[] content() {
      return content;
    }
  }

  /**
   * Where the guest is, to resume the onboarding (CU-26).
   *
   * @throws GuestNotFoundException if there is no guest with that id
   */
  OnboardingView state(Long guestId);

  /**
   * Saves the personal data while the guest is onboarding; the documents step is offered again.
   *
   * @throws OnboardingConflictException if the guest is not onboarding
   */
  OnboardingView savePersonalData(Long guestId, PersonalDataInput data);

  /**
   * Stores one document of the documents step.
   *
   * @throws InvalidOnboardingInputException if the file is not a JPG, PNG or PDF of up to 5 MB
   * @throws OnboardingConflictException out of order, completed or with 5 documents already
   * @throws GuestDocumentUnavailableException if the file store failed; nothing was saved
   */
  DocumentView uploadDocument(Long guestId, DocumentUpload upload);

  /**
   * Closes the documents step, with documents or without them; closing it again changes nothing.
   *
   * @throws OnboardingConflictException out of order or completed
   */
  OnboardingView finishDocuments(Long guestId);

  /**
   * Records the consent and makes the guest ACTIVE, in one transaction.
   *
   * @throws InvalidOnboardingInputException if it is not accepted or not the version in force
   * @throws OnboardingConflictException out of order or completed
   */
  OnboardingView acceptConsent(Long guestId, boolean accepted, String version);
}
