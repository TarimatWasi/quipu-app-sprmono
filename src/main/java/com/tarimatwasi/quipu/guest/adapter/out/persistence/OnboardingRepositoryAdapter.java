package com.tarimatwasi.quipu.guest.adapter.out.persistence;

import com.tarimatwasi.quipu.guest.domain.GuestConsent;
import com.tarimatwasi.quipu.guest.domain.GuestDocument;
import com.tarimatwasi.quipu.guest.port.out.GuestConsentAlreadyExistsException;
import com.tarimatwasi.quipu.guest.port.out.OnboardingRepositoryPort;
import java.util.List;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

@Component
public class OnboardingRepositoryAdapter implements OnboardingRepositoryPort {

  private static final String VERSION_CONSTRAINT = "uq_guest_consents_guest_version";

  private final GuestDocumentJpaRepository documents;
  private final GuestConsentJpaRepository consents;

  OnboardingRepositoryAdapter(
      GuestDocumentJpaRepository documents, GuestConsentJpaRepository consents) {
    this.documents = documents;
    this.consents = consents;
  }

  @Override
  public List<GuestDocument> documentsOf(Long guestId) {
    return documents.findAllByGuestIdOrderByCreatedDateAscIdAsc(guestId).stream()
        .map(GuestDocumentJpaEntity::toDomain)
        .toList();
  }

  @Override
  public int countDocuments(Long guestId) {
    return documents.countByGuestId(guestId);
  }

  /** Flushes, so that a failed insert surfaces here and the stored object can be removed. */
  @Override
  public GuestDocument addDocument(
      Long guestId, String storageKey, String originalName, String contentType, long sizeBytes) {
    return documents
        .saveAndFlush(
            new GuestDocumentJpaEntity(guestId, storageKey, originalName, contentType, sizeBytes))
        .toDomain();
  }

  @Override
  public Optional<GuestConsent> lastConsentOf(Long guestId) {
    return consents
        .findFirstByGuestIdOrderByAcceptedAtDescIdDesc(guestId)
        .map(GuestConsentJpaEntity::toDomain);
  }

  @Override
  public Optional<GuestConsent> consentOf(Long guestId, String version) {
    return consents.findByGuestIdAndVersion(guestId, version).map(GuestConsentJpaEntity::toDomain);
  }

  /** Flushes, so that a second consent of the version surfaces here as what it is. */
  @Override
  public void addConsent(GuestConsent consent) {
    try {
      consents.saveAndFlush(new GuestConsentJpaEntity(consent));
    } catch (DataIntegrityViolationException e) {
      throw conflict(e);
    }
  }

  /** Only the rule of one consent per version is translated; any other failure stays as it is. */
  static RuntimeException conflict(DataIntegrityViolationException e) {
    for (Throwable cause = e; cause != null; cause = cause.getCause()) {
      if (cause instanceof ConstraintViolationException violation
          && VERSION_CONSTRAINT.equals(violation.getConstraintName())) {
        return new GuestConsentAlreadyExistsException(e);
      }
    }
    return e;
  }
}
