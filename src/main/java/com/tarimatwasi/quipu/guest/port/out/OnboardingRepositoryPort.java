package com.tarimatwasi.quipu.guest.port.out;

import com.tarimatwasi.quipu.guest.domain.GuestConsent;
import com.tarimatwasi.quipu.guest.domain.GuestDocument;
import java.util.List;
import java.util.Optional;

/** The documents and the consents of the onboarding; both are only ever inserted. */
public interface OnboardingRepositoryPort {

  /** The documents of the guest, oldest first, without those whose deletion is in course. */
  List<GuestDocument> documentsOf(Long guestId);

  /** How many of those there are: a document whose deletion is in course no longer counts. */
  int countDocuments(Long guestId);

  /** Inserts the document row; the store sets its id and its time. */
  GuestDocument addDocument(
      Long guestId, String storageKey, String originalName, String contentType, long sizeBytes);

  /** The last consent the guest accepted, if any. */
  Optional<GuestConsent> lastConsentOf(Long guestId);

  /** The consent of the guest for this version of the text, if any; there is one per version. */
  Optional<GuestConsent> consentOf(Long guestId, String version);

  void addConsent(GuestConsent consent);
}
