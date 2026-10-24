package com.tarimatwasi.quipu.guest.application;

import com.tarimatwasi.quipu.guest.domain.EmergencyContact;
import com.tarimatwasi.quipu.guest.domain.Guest;
import com.tarimatwasi.quipu.guest.domain.GuestConsent;
import com.tarimatwasi.quipu.guest.domain.GuestDocument;
import com.tarimatwasi.quipu.guest.domain.GuestStatus;
import com.tarimatwasi.quipu.guest.port.in.GuestDocumentUnavailableException;
import com.tarimatwasi.quipu.guest.port.in.GuestEmailTakenException;
import com.tarimatwasi.quipu.guest.port.in.GuestNotFoundException;
import com.tarimatwasi.quipu.guest.port.in.InvalidOnboardingInputException;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase.EmergencyContactView;
import com.tarimatwasi.quipu.guest.port.in.OnboardingConflictException;
import com.tarimatwasi.quipu.guest.port.in.OnboardingConflictException.Reason;
import com.tarimatwasi.quipu.guest.port.in.OnboardingUseCase;
import com.tarimatwasi.quipu.guest.port.out.GuestConsentAlreadyExistsException;
import com.tarimatwasi.quipu.guest.port.out.GuestDocumentFilePort;
import com.tarimatwasi.quipu.guest.port.out.GuestDocumentStorageException;
import com.tarimatwasi.quipu.guest.port.out.GuestEmailAlreadyExistsException;
import com.tarimatwasi.quipu.guest.port.out.GuestRepositoryPort;
import com.tarimatwasi.quipu.guest.port.out.OnboardingRepositoryPort;
import com.tarimatwasi.quipu.shared.file.FileKind;
import com.tarimatwasi.quipu.shared.file.FileNames;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Every step locks the row of the guest first, so two requests of one guest are applied one by one
 * and the count of documents cannot be passed by two simultaneous uploads.
 */
@Service
@PreAuthorize("hasRole('GUEST')") // BE-SPR-SEC-02: guest use case, besides the URL rule
public class OnboardingService implements OnboardingUseCase {

  private static final Logger LOGGER = LoggerFactory.getLogger(OnboardingService.class);

  /** The name of a document whose browser name is unusable. */
  private static final String DEFAULT_NAME = "documento";

  private final GuestRepositoryPort guests;
  private final OnboardingRepositoryPort records;
  private final GuestDocumentFilePort files;
  private final ConsentTexts consents;
  private final Clock clock;

  OnboardingService(
      GuestRepositoryPort guests,
      OnboardingRepositoryPort records,
      GuestDocumentFilePort files,
      ConsentTexts consents,
      Clock clock) {
    this.guests = guests;
    this.records = records;
    this.files = files;
    this.consents = consents;
    this.clock = clock;
  }

  @Override
  @Transactional(readOnly = true)
  public OnboardingView state(Long guestId) {
    return view(guests.findById(guestId).orElseThrow(GuestNotFoundException::new));
  }

  @Override
  @Transactional
  public OnboardingView savePersonalData(Long guestId, PersonalData data) {
    Guest guest = onboarding(guestId);
    Guest changed =
        new Guest(
            guest.id(),
            guest.documentType(),
            guest.documentNumber(),
            data.name(),
            data.contactEmail(),
            data.phone(),
            guest.type(),
            guest.status(),
            guest.statusBeforeInactive(),
            clock.instant(),
            // The documents step answers the personal data step: it is offered again.
            null,
            emergencyContact(data.emergencyContact()));
    try {
      return view(guests.save(changed));
    } catch (GuestEmailAlreadyExistsException e) {
      throw new GuestEmailTakenException(e);
    }
  }

  @Override
  @Transactional
  public DocumentView uploadDocument(Long guestId, DocumentUpload upload) {
    byte[] content = upload.content();
    FileKind kind =
        (content.length > GuestDocument.MAX_BYTES ? null : FileKind.of(content).orElse(null));
    if (content.length == 0 || kind == null) {
      throw new InvalidOnboardingInputException(
          InvalidOnboardingInputException.Reason.DOCUMENT_INVALID_FILE);
    }
    Guest guest = onboarding(guestId);
    if (guest.personalDataCompletedAt() == null || guest.documentsStepCompletedAt() != null) {
      throw new OnboardingConflictException(Reason.STEP_OUT_OF_ORDER);
    }
    if (records.countDocuments(guestId) >= GuestDocument.MAX_PER_GUEST) {
      throw new OnboardingConflictException(Reason.DOCUMENT_LIMIT_REACHED);
    }
    String name = FileNames.sanitized(upload.originalName(), DEFAULT_NAME);
    String key = store(content, kind);
    boolean removedIfNotCommitted = removeUnlessCommitted(key);
    GuestDocument saved = null;
    try {
      saved = records.addDocument(guestId, key, name, kind.contentType(), content.length);
    } finally {
      if (saved == null && !removedIfNotCommitted) {
        discard(key);
      }
    }
    return document(saved);
  }

  @Override
  @Transactional
  public OnboardingView finishDocuments(Long guestId) {
    Guest guest = onboarding(guestId);
    if (guest.personalDataCompletedAt() == null) {
      throw new OnboardingConflictException(Reason.STEP_OUT_OF_ORDER);
    }
    if (guest.documentsStepCompletedAt() != null) {
      return view(guest);
    }
    return view(
        guests.save(
            new Guest(
                guest.id(),
                guest.documentType(),
                guest.documentNumber(),
                guest.fullName(),
                guest.email(),
                guest.phone(),
                guest.type(),
                guest.status(),
                guest.statusBeforeInactive(),
                guest.personalDataCompletedAt(),
                clock.instant(),
                guest.emergencyContact())));
  }

  @Override
  @Transactional
  public OnboardingView acceptConsent(Long guestId, boolean accepted, String version) {
    if (!accepted) {
      throw new InvalidOnboardingInputException(
          InvalidOnboardingInputException.Reason.CONSENT_NOT_ACCEPTED);
    }
    if (!consents.current().equals(version)) {
      throw new InvalidOnboardingInputException(
          InvalidOnboardingInputException.Reason.CONSENT_VERSION);
    }
    Guest guest = onboarding(guestId);
    if (guest.personalDataCompletedAt() == null || guest.documentsStepCompletedAt() == null) {
      throw new OnboardingConflictException(Reason.STEP_OUT_OF_ORDER);
    }
    // RN-14, RN-36: the record of the consent and the activation commit together. D-49: one record
    // per version; the lock on the guest keeps two requests from both inserting it.
    String textSha256 = ConsentTexts.sha256(consents.currentText());
    GuestConsent recorded = records.consentOf(guestId, version).orElse(null);
    if (recorded == null) {
      try {
        records.addConsent(new GuestConsent(guestId, version, clock.instant(), textSha256));
      } catch (GuestConsentAlreadyExistsException e) {
        throw new OnboardingConflictException(Reason.ALREADY_COMPLETED);
      }
    } else if (!recorded.textSha256().equals(textSha256)) {
      // The text of the version changed after it was accepted: the record cannot prove this one.
      throw new OnboardingConflictException(Reason.ALREADY_COMPLETED);
    }
    return view(guests.save(GuestService.withStatus(guest, GuestStatus.ACTIVE, null)));
  }

  /** The guest of the session, locked, if it is still onboarding. */
  private Guest onboarding(Long guestId) {
    Guest guest = guests.findByIdForUpdate(guestId).orElseThrow(GuestNotFoundException::new);
    if (guest.status() == GuestStatus.ACTIVE) {
      throw new OnboardingConflictException(Reason.ALREADY_COMPLETED);
    }
    if (guest.status() != GuestStatus.ONBOARDING) {
      // Pending activation (the password step is not done) or inactive.
      throw new OnboardingConflictException(Reason.STEP_OUT_OF_ORDER);
    }
    return guest;
  }

  private OnboardingView view(Guest guest) {
    List<DocumentView> documents =
        records.documentsOf(guest.id()).stream().map(OnboardingService::document).toList();
    if (guest.status() == GuestStatus.ACTIVE) {
      // A guest made active before the consents were recorded shows the text in force.
      ConsentView consent =
          records
              .lastConsentOf(guest.id())
              .map(c -> new ConsentView(c.version(), ConsentTexts.textOf(c.version()), true))
              .orElseGet(() -> new ConsentView(consents.current(), consents.currentText(), true));
      return new OnboardingView(true, null, personalData(guest), documents, consent);
    }
    return new OnboardingView(
        false,
        GuestSessionService.nextStep(guest),
        guest.personalDataCompletedAt() == null ? null : personalData(guest),
        documents,
        new ConsentView(consents.current(), consents.currentText(), false));
  }

  private static PersonalData personalData(Guest guest) {
    EmergencyContact contact = guest.emergencyContact();
    return new PersonalData(
        Objects.requireNonNullElse(guest.fullName(), ""),
        Objects.requireNonNullElse(guest.phone(), ""),
        guest.email(),
        contact == null
            ? null
            : new EmergencyContactView(contact.name(), contact.relationship(), contact.phone()));
  }

  private static @Nullable EmergencyContact emergencyContact(
      @Nullable EmergencyContactView contact) {
    if (contact == null
        || (contact.name() == null && contact.relationship() == null && contact.phone() == null)) {
      return null;
    }
    return new EmergencyContact(contact.name(), contact.relationship(), contact.phone());
  }

  private static DocumentView document(GuestDocument document) {
    return new DocumentView(
        document.id(),
        document.originalName(),
        document.contentType(),
        document.sizeBytes(),
        document.uploadedAt());
  }

  private String store(byte[] content, FileKind kind) {
    try {
      return files.store(content, kind);
    } catch (GuestDocumentStorageException e) {
      throw new GuestDocumentUnavailableException(e);
    }
  }

  /**
   * Inside a transaction the object is removed whenever the transaction does not commit: the insert
   * failed, or something after it did. Returns true if it will be; otherwise the caller removes it.
   */
  private boolean removeUnlessCommitted(String key) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      return false;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (status != STATUS_COMMITTED) {
              discard(key);
            }
          }
        });
    return true;
  }

  /** A failed cleanup is logged with the random key only, never the name nor the content. */
  private void discard(String key) {
    try {
      files.delete(key);
    } catch (GuestDocumentStorageException cleanupFailed) {
      LOGGER.error("The guest document {} stays in the bucket without a row", key, cleanupFailed);
    }
  }
}
