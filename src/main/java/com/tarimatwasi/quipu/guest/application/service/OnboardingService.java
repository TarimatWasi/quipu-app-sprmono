package com.tarimatwasi.quipu.guest.application.service;

import com.tarimatwasi.quipu.guest.application.port.in.GuestNotFoundException;
import com.tarimatwasi.quipu.guest.application.port.in.InvalidOnboardingInputException;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.EmergencyContactView;
import com.tarimatwasi.quipu.guest.application.port.in.OnboardingConflictException;
import com.tarimatwasi.quipu.guest.application.port.in.OnboardingConflictException.Reason;
import com.tarimatwasi.quipu.guest.application.port.in.OnboardingUseCase;
import com.tarimatwasi.quipu.guest.application.port.out.GuestAccountPort;
import com.tarimatwasi.quipu.guest.application.port.out.GuestConsentAlreadyExistsException;
import com.tarimatwasi.quipu.guest.application.port.out.GuestRepositoryPort;
import com.tarimatwasi.quipu.guest.application.port.out.OnboardingRepositoryPort;
import com.tarimatwasi.quipu.guest.domain.EmergencyContact;
import com.tarimatwasi.quipu.guest.domain.Guest;
import com.tarimatwasi.quipu.guest.domain.GuestConsent;
import com.tarimatwasi.quipu.guest.domain.GuestStatus;
import com.tarimatwasi.quipu.shared.changes.ChangeSignals;
import com.tarimatwasi.quipu.shared.file.FileKind;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every step locks the row of the guest first, so two requests of one guest are applied one by one
 * and the count of documents cannot be passed by two simultaneous uploads.
 */
@Service
@PreAuthorize("hasRole('GUEST')") // BE-SPR-SEC-02: guest use case, besides the URL rule
public class OnboardingService implements OnboardingUseCase {

  private final ChangeSignals changes;
  private final GuestRepositoryPort guests;
  private final GuestAccountPort accounts;
  private final OnboardingRepositoryPort records;
  private final GuestDocumentUploader uploader;
  private final ConsentTexts consents;
  private final Clock clock;

  OnboardingService(
      GuestRepositoryPort guests,
      GuestAccountPort accounts,
      OnboardingRepositoryPort records,
      GuestDocumentUploader uploader,
      ConsentTexts consents,
      Clock clock,
      ChangeSignals changes) {
    this.changes = changes;
    this.guests = guests;
    this.accounts = accounts;
    this.records = records;
    this.uploader = uploader;
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
  public OnboardingView savePersonalData(Long guestId, PersonalDataInput data) {
    Guest guest = onboarding(guestId);
    Guest changed =
        new Guest(
            guest.id(),
            guest.documentType(),
            guest.documentNumber(),
            data.name(),
            data.phone(),
            guest.type(),
            guest.status(),
            guest.statusBeforeInactive(),
            clock.instant(),
            // The documents step answers the personal data step: it is offered again.
            null,
            emergencyContact(data.emergencyContact()));
    Guest saved = saved(changed);
    if (!Objects.equals(guest.fullName(), saved.fullName())) {
      changes.changed(
          new ChangeSignals.Change(ChangeSignals.Kind.DIRECTORY, guestId, false, null, null));
    }
    return view(saved);
  }

  @Override
  @Transactional
  public DocumentView uploadDocument(Long guestId, DocumentUpload upload) {
    FileKind kind = GuestDocumentUploader.kindOf(upload);
    Guest guest = onboarding(guestId);
    if (guest.personalDataCompletedAt() == null || guest.documentsStepCompletedAt() != null) {
      throw new OnboardingConflictException(Reason.STEP_OUT_OF_ORDER);
    }
    return uploader.store(guestId, upload, kind);
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
        saved(
            new Guest(
                guest.id(),
                guest.documentType(),
                guest.documentNumber(),
                guest.fullName(),
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
    return view(saved(GuestService.withStatus(guest, GuestStatus.ACTIVE, null)));
  }

  private Guest saved(Guest guest) {
    Guest saved = guests.save(guest);
    changes.changed(
        new ChangeSignals.Change(ChangeSignals.Kind.GUEST_RECORD, guest.id(), false, null, null));
    return saved;
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
        records.documentsOf(guest.id()).stream().map(GuestDocumentUploader::view).toList();
    if (guest.status() == GuestStatus.ACTIVE) {
      // Only the text in force is published (TAR-305), so an active guest sees that one, even if
      // the version they accepted was an earlier one: guest_consents keeps what they accepted.
      ConsentView consent = new ConsentView(consents.current(), consents.currentText(), true);
      return new OnboardingView(true, null, personalData(guest), documents, consent);
    }
    return new OnboardingView(
        false,
        GuestSessionService.nextStep(guest),
        personalData(guest),
        documents,
        new ConsentView(consents.current(), consents.currentText(), false));
  }

  /** Before the step is saved the name and the phone are empty; the email is the account's. */
  private PersonalData personalData(Guest guest) {
    EmergencyContact contact = guest.emergencyContact();
    return new PersonalData(
        Objects.requireNonNullElse(guest.fullName(), ""),
        Objects.requireNonNullElse(guest.phone(), ""),
        accounts.accountEmail(guest.id()).orElse(null),
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
}
