package com.tarimatwasi.quipu.guest.port.out;

import com.tarimatwasi.quipu.guest.domain.GuestConsent;
import com.tarimatwasi.quipu.guest.domain.GuestDocument;
import java.util.List;
import java.util.Optional;

/** The documents and the consents of the onboarding; both are only ever inserted. */
public interface OnboardingRepositoryPort {

  /** The documents of the guest, oldest first. */
  List<GuestDocument> documentsOf(Long guestId);

  int countDocuments(Long guestId);

  /** Inserts the document row; the store sets its id and its time. */
  GuestDocument addDocument(
      Long guestId, String storageKey, String originalName, String contentType, long sizeBytes);

  /** The last consent the guest accepted, if any. */
  Optional<GuestConsent> lastConsentOf(Long guestId);

  void addConsent(GuestConsent consent);
}
