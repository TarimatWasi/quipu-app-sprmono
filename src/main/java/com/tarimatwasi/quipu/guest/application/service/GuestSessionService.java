package com.tarimatwasi.quipu.guest.application.service;

import com.tarimatwasi.quipu.guest.application.port.in.GuestNotFoundException;
import com.tarimatwasi.quipu.guest.application.port.in.GuestSessionUseCase;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.GuestKind;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.GuestState;
import com.tarimatwasi.quipu.guest.application.port.out.GuestRepositoryPort;
import com.tarimatwasi.quipu.guest.domain.Guest;
import com.tarimatwasi.quipu.guest.domain.GuestStatus;
import com.tarimatwasi.quipu.shared.access.GuestAccessListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The guest behind a session that is being opened. No role check: it runs during the login and the
 * activation, before there is a session, and the caller passes the guest of the account it has just
 * authenticated.
 */
@Service
public class GuestSessionService implements GuestSessionUseCase, GuestAccessListener {

  private final GuestRepositoryPort guests;

  GuestSessionService(GuestRepositoryPort guests) {
    this.guests = guests;
  }

  @Override
  @Transactional(readOnly = true)
  public GuestSessionView sessionOf(Long guestId) {
    Guest guest = guests.findById(guestId).orElseThrow(GuestNotFoundException::new);
    String fullName = guest.fullName();
    String name =
        fullName != null ? fullName : guest.documentType().name() + " " + guest.documentNumber();
    return new GuestSessionView(
        GuestKind.valueOf(guest.type().name()),
        GuestState.valueOf(guest.status().name()),
        name,
        guest.status() == GuestStatus.ONBOARDING ? nextStep(guest) : null);
  }

  @Override
  @Transactional
  public void lockGuest(Long guestId) {
    guests.findByIdForUpdate(guestId);
  }

  /** D-30: the password chosen with an access opens the onboarding; any other state is kept. */
  @Override
  @Transactional
  public void accessUsed(Long guestId) {
    guests
        .findByIdForUpdate(guestId)
        .filter(guest -> guest.status() == GuestStatus.PENDING_ACTIVATION)
        .ifPresent(
            guest -> guests.save(GuestService.withStatus(guest, GuestStatus.ONBOARDING, null)));
  }

  /** RN-33 order after the password: personal data, documents, consent. */
  static OnboardingStep nextStep(Guest guest) {
    if (guest.personalDataCompletedAt() == null) {
      return OnboardingStep.PERSONAL_DATA;
    }
    return guest.documentsStepCompletedAt() == null
        ? OnboardingStep.DOCUMENTS
        : OnboardingStep.CONSENT;
  }
}
