package com.tarimatwasi.quipu.guest.application;

import com.tarimatwasi.quipu.guest.domain.EmergencyContact;
import com.tarimatwasi.quipu.guest.domain.Guest;
import com.tarimatwasi.quipu.guest.domain.GuestStatus;
import com.tarimatwasi.quipu.guest.port.in.EmptyGuestUpdateException;
import com.tarimatwasi.quipu.guest.port.in.GuestEmailTakenException;
import com.tarimatwasi.quipu.guest.port.in.GuestNotFoundException;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase.EmergencyContactView;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase.GuestKind;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase.GuestSummaryView;
import com.tarimatwasi.quipu.guest.port.in.OwnProfileRefusedException;
import com.tarimatwasi.quipu.guest.port.in.OwnProfileRefusedException.Reason;
import com.tarimatwasi.quipu.guest.port.in.OwnProfileUseCase;
import com.tarimatwasi.quipu.guest.port.out.GuestAccountPort;
import com.tarimatwasi.quipu.guest.port.out.GuestAccountPort.AccountState;
import com.tarimatwasi.quipu.guest.port.out.GuestEmailAlreadyExistsException;
import com.tarimatwasi.quipu.guest.port.out.GuestRepositoryPort;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The row of the guest is locked before it is read (as every step of the onboarding does), so two
 * edits of one guest are applied one by one; the version of the row still guards any other writer.
 * A contact email of another guest is refused by the unique index, so two guests that race for one
 * are a 409 and never a 500.
 */
@Service
@PreAuthorize("hasRole('GUEST')") // BE-SPR-SEC-02: guest use case, besides the URL rule
public class OwnProfileService implements OwnProfileUseCase {

  private static final AccountState NO_ACCOUNT = new AccountState(false, false);

  private final GuestRepositoryPort guests;
  private final GuestAccountPort accounts;

  OwnProfileService(GuestRepositoryPort guests, GuestAccountPort accounts) {
    this.guests = guests;
    this.accounts = accounts;
  }

  @Override
  @Transactional
  public GuestSummaryView update(Long guestId, GuestKind area, ProfileChange change) {
    if (change.empty()) {
      throw new EmptyGuestUpdateException();
    }
    Guest guest = guests.findByIdForUpdate(guestId).orElseThrow(GuestNotFoundException::new);
    // RN-09, RN-33: the guard of the guest areas refuses both first; checked again under the lock.
    if (!guest.type().name().equals(area.name())) {
      throw new OwnProfileRefusedException(Reason.OTHER_GUEST_TYPE);
    }
    if (guest.status() == GuestStatus.PENDING_ACTIVATION
        || guest.status() == GuestStatus.ONBOARDING) {
      throw new OwnProfileRefusedException(Reason.ONBOARDING_REQUIRED);
    }
    Guest saved;
    try {
      saved = guests.save(changed(guest, change));
    } catch (GuestEmailAlreadyExistsException e) {
      throw new GuestEmailTakenException(e);
    }
    AccountState account =
        accounts.stateOf(Set.of(saved.id())).getOrDefault(saved.id(), NO_ACCOUNT);
    return GuestService.summary(saved, account);
  }

  /** RN-17: only the personal and contact data; the document, the type and the state stay. */
  private static Guest changed(Guest guest, ProfileChange change) {
    EmergencyContactView contact = change.emergencyContact();
    return new Guest(
        guest.id(),
        guest.documentType(),
        guest.documentNumber(),
        or(change.name(), guest.fullName()),
        or(change.contactEmail(), guest.email()),
        or(change.phone(), guest.phone()),
        guest.type(),
        guest.status(),
        guest.statusBeforeInactive(),
        guest.personalDataCompletedAt(),
        guest.documentsStepCompletedAt(),
        contact == null ? guest.emergencyContact() : emergencyContact(contact));
  }

  /** The new value when present, else the stored one, which may be missing. */
  private static @Nullable String or(@Nullable String changed, @Nullable String stored) {
    return changed != null ? changed : stored;
  }

  private static @Nullable EmergencyContact emergencyContact(EmergencyContactView contact) {
    if (contact.name() == null && contact.relationship() == null && contact.phone() == null) {
      return null;
    }
    return new EmergencyContact(contact.name(), contact.relationship(), contact.phone());
  }
}
