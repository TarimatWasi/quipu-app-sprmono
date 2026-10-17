package com.tarimatwasi.quipu.guest.application;

import com.tarimatwasi.quipu.guest.domain.DocumentType;
import com.tarimatwasi.quipu.guest.domain.EmergencyContact;
import com.tarimatwasi.quipu.guest.domain.Guest;
import com.tarimatwasi.quipu.guest.domain.GuestStatus;
import com.tarimatwasi.quipu.guest.domain.GuestType;
import com.tarimatwasi.quipu.guest.domain.Stay;
import com.tarimatwasi.quipu.guest.port.in.EmptyGuestUpdateException;
import com.tarimatwasi.quipu.guest.port.in.GuestDocumentLockedException;
import com.tarimatwasi.quipu.guest.port.in.GuestDocumentTakenException;
import com.tarimatwasi.quipu.guest.port.in.GuestEmailTakenException;
import com.tarimatwasi.quipu.guest.port.in.GuestNotFoundException;
import com.tarimatwasi.quipu.guest.port.in.InvalidGuestDocumentException;
import com.tarimatwasi.quipu.guest.port.in.LockGuestUseCase;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase;
import com.tarimatwasi.quipu.guest.port.out.GuestAccountPort;
import com.tarimatwasi.quipu.guest.port.out.GuestAccountPort.AccountState;
import com.tarimatwasi.quipu.guest.port.out.GuestDocumentAlreadyExistsException;
import com.tarimatwasi.quipu.guest.port.out.GuestEmailAlreadyExistsException;
import com.tarimatwasi.quipu.guest.port.out.GuestRepositoryPort;
import com.tarimatwasi.quipu.guest.port.out.StayRepositoryPort;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@PreAuthorize("hasRole('ADMIN')") // BE-SPR-SEC-02 (TAR-170): admin use case, besides the URL rule
public class GuestService implements ManageGuestsUseCase, LockGuestUseCase {

  private static final Pattern DNI = Pattern.compile("[0-9]{8}");
  private static final Pattern OTHER_DOCUMENT = Pattern.compile("[A-Za-z0-9]{1,20}");
  private static final AccountState NO_ACCOUNT = new AccountState(false, false);

  private final GuestRepositoryPort guests;
  private final GuestAccountPort accounts;
  private final StayRepositoryPort stays;
  private final Clock clock;

  GuestService(
      GuestRepositoryPort guests,
      GuestAccountPort accounts,
      StayRepositoryPort stays,
      Clock clock) {
    this.stays = stays;
    this.clock = clock;
    this.guests = guests;
    this.accounts = accounts;
  }

  @Override
  @Transactional(readOnly = true)
  public CursorPage<GuestSummaryView> list(
      StatusFilter filter, @Nullable GuestKind type, PageQuery page) {
    CursorPage<Guest> found =
        guests.findAll(statuses(filter), type == null ? null : toType(type), page);
    Set<Long> ids = new LinkedHashSet<>();
    found.content().forEach(guest -> ids.add(guest.id()));
    Map<Long, AccountState> states = ids.isEmpty() ? Map.of() : accounts.stateOf(ids);
    return found.map(guest -> summary(guest, states.getOrDefault(guest.id(), NO_ACCOUNT)));
  }

  @Override
  @Transactional(readOnly = true)
  public GuestDetailView get(Long id) {
    Guest guest = guests.findById(id).orElseThrow(GuestNotFoundException::new);
    AccountState account = stateOf(guest);
    Stay stay = Stay.relevantOn(stays.findAllByGuest(id), LocalDate.now(clock)).orElse(null);
    return new GuestDetailView(
        summary(guest, account),
        guest.phone(),
        guest.email(),
        emergencyContact(guest.emergencyContact()),
        stay == null ? null : stay.startDate(),
        stay == null ? null : stay.endDate(),
        stay == null ? null : stay.agreedAmount(),
        progress(guest, account),
        // The documents of the onboarding and the access of RF-11 arrive with TAR-137 and TAR-36.
        0,
        null);
  }

  @Override
  @Transactional
  public void lock(Long id) {
    if (guests.findByIdForUpdate(id).isEmpty()) {
      throw new GuestNotFoundException();
    }
  }

  @Override
  @Transactional
  public GuestSummaryView update(Long id, UpdateCommand command) {
    if (command.documentType() == null && command.documentNumber() == null) {
      throw new EmptyGuestUpdateException();
    }
    Guest current = guests.findByIdForUpdate(id).orElseThrow(GuestNotFoundException::new);
    Guest changed = withDocument(current, command);
    try {
      Guest saved = guests.save(changed);
      return summary(saved, stateOf(saved));
    } catch (GuestDocumentAlreadyExistsException e) {
      throw new GuestDocumentTakenException(e);
    } catch (GuestEmailAlreadyExistsException e) {
      throw new GuestEmailTakenException(e);
    }
  }

  private Guest withDocument(Guest guest, UpdateCommand command) {
    if (command.documentType() == null && command.documentNumber() == null) {
      return guest;
    }
    DocumentType type =
        command.documentType() == null ? guest.documentType() : toType(command.documentType());
    String number = documentNumber(type, command.documentNumber(), guest.documentNumber());
    if (type == guest.documentType() && number.equals(guest.documentNumber())) {
      return guest;
    }
    if (!guest.documentCanBeCorrected()) {
      throw new GuestDocumentLockedException();
    }
    if (accounts.documentBelongsToAdministrator(type.name(), number)) {
      throw new GuestDocumentTakenException();
    }
    return new Guest(
        guest.id(),
        type,
        number,
        guest.fullName(),
        guest.email(),
        guest.phone(),
        guest.type(),
        guest.status(),
        guest.statusBeforeInactive(),
        guest.personalDataCompletedAt(),
        guest.documentsStepCompletedAt(),
        guest.emergencyContact());
  }

  /** The stored number has no edge spaces and fits its type; CE and passport are upper case. */
  private static String documentNumber(
      DocumentType type, @Nullable String requested, String current) {
    String number = (requested == null ? current : requested).strip();
    boolean valid =
        type == DocumentType.DNI
            ? DNI.matcher(number).matches()
            : OTHER_DOCUMENT.matcher(number).matches();
    if (!valid) {
      throw new InvalidGuestDocumentException();
    }
    return type == DocumentType.DNI ? number : number.toUpperCase(Locale.ROOT);
  }

  private AccountState stateOf(Guest guest) {
    return accounts.stateOf(Set.of(guest.id())).getOrDefault(guest.id(), NO_ACCOUNT);
  }

  /** RN-33 order: password, personal data, documents; ACTIVE is completed (RN-36). */
  private static OnboardingProgress progress(Guest guest, AccountState account) {
    GuestStatus status =
        guest.status() == GuestStatus.INACTIVE && guest.statusBeforeInactive() != null
            ? guest.statusBeforeInactive()
            : guest.status();
    return switch (status) {
      case ACTIVE -> OnboardingProgress.COMPLETED;
      case PENDING_ACTIVATION, INACTIVE -> OnboardingProgress.NOT_STARTED;
      case ONBOARDING -> onboardingProgress(guest, account);
    };
  }

  private static OnboardingProgress onboardingProgress(Guest guest, AccountState account) {
    if (guest.documentsStepCompletedAt() != null) {
      return OnboardingProgress.DOCUMENTS_DONE;
    }
    if (guest.personalDataCompletedAt() != null) {
      return OnboardingProgress.PERSONAL_DATA_SAVED;
    }
    return account.passwordChosen()
        ? OnboardingProgress.PASSWORD_SET
        : OnboardingProgress.NOT_STARTED;
  }

  private static GuestSummaryView summary(Guest guest, AccountState account) {
    return new GuestSummaryView(
        guest.id(),
        IdDocumentKind.valueOf(guest.documentType().name()),
        guest.documentNumber(),
        guest.fullName(),
        GuestKind.valueOf(guest.type().name()),
        GuestState.valueOf(guest.status().name()),
        account.canLogIn());
  }

  private static @Nullable EmergencyContactView emergencyContact(
      @Nullable EmergencyContact contact) {
    return contact == null
        ? null
        : new EmergencyContactView(contact.name(), contact.relationship(), contact.phone());
  }

  /** {@code null}: every status. */
  private static @Nullable List<GuestStatus> statuses(StatusFilter filter) {
    return switch (filter) {
      case CURRENT ->
          List.of(GuestStatus.PENDING_ACTIVATION, GuestStatus.ONBOARDING, GuestStatus.ACTIVE);
      case PENDING_ACTIVATION -> List.of(GuestStatus.PENDING_ACTIVATION);
      case ONBOARDING -> List.of(GuestStatus.ONBOARDING);
      case ACTIVE -> List.of(GuestStatus.ACTIVE);
      case INACTIVE -> List.of(GuestStatus.INACTIVE);
      case ALL -> null;
    };
  }

  private static GuestType toType(GuestKind kind) {
    return GuestType.valueOf(kind.name());
  }

  private static DocumentType toType(IdDocumentKind kind) {
    return DocumentType.valueOf(kind.name());
  }
}
