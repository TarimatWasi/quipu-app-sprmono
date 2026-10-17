package com.tarimatwasi.quipu.guest.application;

import com.tarimatwasi.quipu.guest.domain.DocumentType;
import com.tarimatwasi.quipu.guest.domain.Guest;
import com.tarimatwasi.quipu.guest.domain.GuestStatus;
import com.tarimatwasi.quipu.guest.domain.GuestType;
import com.tarimatwasi.quipu.guest.port.in.GuestDocumentTakenException;
import com.tarimatwasi.quipu.guest.port.in.GuestEmailTakenException;
import com.tarimatwasi.quipu.guest.port.in.GuestInactiveException;
import com.tarimatwasi.quipu.guest.port.in.GuestNotFoundException;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestAccessUseCase.RegisterCommand;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase.GuestSummaryView;
import com.tarimatwasi.quipu.guest.port.out.GuestAccountPort;
import com.tarimatwasi.quipu.guest.port.out.GuestAccountPort.AccountState;
import com.tarimatwasi.quipu.guest.port.out.GuestAccountPort.IssuedAccess;
import com.tarimatwasi.quipu.guest.port.out.GuestDocumentAlreadyExistsException;
import com.tarimatwasi.quipu.guest.port.out.GuestEmailAlreadyExistsException;
import com.tarimatwasi.quipu.guest.port.out.GuestRepositoryPort;
import com.tarimatwasi.quipu.shared.access.AccessMode;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The database half of {@link GuestRegistrationService}: its own bean so that the transaction
 * commits before the email is queued, and an email never carries an access that was not committed.
 */
@Service
class GuestAccessIssuer {

  /** What was committed; {@code access} is null for an already registered document. */
  record Issued(
      @Nullable GuestSummaryView guest, @Nullable IssuedAccess access, @Nullable String email) {}

  private final GuestRepositoryPort guests;
  private final GuestAccountPort accounts;

  GuestAccessIssuer(GuestRepositoryPort guests, GuestAccountPort accounts) {
    this.guests = guests;
    this.accounts = accounts;
  }

  @Transactional
  Issued register(RegisterCommand command) {
    DocumentType type = DocumentType.valueOf(command.documentType().name());
    String number = GuestService.documentNumber(type, command.documentNumber(), "");
    if (accounts.documentBelongsToAdministrator(type.name(), number)) {
      throw new GuestDocumentTakenException();
    }
    Optional<Guest> existing = guests.findByDocument(type, number);
    if (existing.isPresent()) {
      Guest guest = existing.get();
      if (guest.status() == GuestStatus.INACTIVE) {
        throw new GuestInactiveException(guest.id());
      }
      return new Issued(GuestService.summary(guest, stateOf(guest)), null, null);
    }
    String email = blankToNull(command.contactEmail());
    Guest created;
    try {
      created =
          guests.create(
              type,
              number,
              GuestType.valueOf(command.type().name()),
              blankToNull(command.phone()),
              email);
    } catch (GuestEmailAlreadyExistsException e) {
      throw new GuestEmailTakenException(e);
    } catch (GuestDocumentAlreadyExistsException e) {
      // ponytail: two simultaneous registrations of one document; the loser gets a 409, not RN-18.
      throw new GuestDocumentTakenException(e);
    }
    accounts.openAccount(created.id(), type.name(), number, email);
    IssuedAccess access = accounts.issueAccess(created.id(), command.accessMode());
    return new Issued(GuestService.summary(created, stateOf(created)), access, email);
  }

  /** The guest row stays locked until the commit: two regenerations are applied one by one. */
  @Transactional
  Issued generate(Long guestId, AccessMode mode) {
    Guest guest = guests.findByIdForUpdate(guestId).orElseThrow(GuestNotFoundException::new);
    if (guest.status() == GuestStatus.INACTIVE) {
      throw new GuestInactiveException(guestId);
    }
    // A guest registered before RF-11 has no account yet.
    accounts.openAccount(
        guestId, guest.documentType().name(), guest.documentNumber(), guest.email());
    return new Issued(null, accounts.issueAccess(guestId, mode), guest.email());
  }

  private AccountState stateOf(Guest guest) {
    return accounts
        .stateOf(Set.of(guest.id()))
        .getOrDefault(guest.id(), new AccountState(false, false));
  }

  private static @Nullable String blankToNull(@Nullable String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
