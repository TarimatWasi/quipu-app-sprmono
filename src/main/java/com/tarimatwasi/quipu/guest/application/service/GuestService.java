package com.tarimatwasi.quipu.guest.application.service;

import com.tarimatwasi.quipu.guest.application.port.in.EmptyGuestUpdateException;
import com.tarimatwasi.quipu.guest.application.port.in.GuestDocumentLockedException;
import com.tarimatwasi.quipu.guest.application.port.in.GuestDocumentTakenException;
import com.tarimatwasi.quipu.guest.application.port.in.GuestEmailTakenException;
import com.tarimatwasi.quipu.guest.application.port.in.GuestHasActiveContractException;
import com.tarimatwasi.quipu.guest.application.port.in.GuestNotFoundException;
import com.tarimatwasi.quipu.guest.application.port.in.InvalidGuestDocumentException;
import com.tarimatwasi.quipu.guest.application.port.in.LockGuestUseCase;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase;
import com.tarimatwasi.quipu.guest.application.port.out.GuestAccountPort;
import com.tarimatwasi.quipu.guest.application.port.out.GuestAccountPort.AccountState;
import com.tarimatwasi.quipu.guest.application.port.out.GuestDocumentAlreadyExistsException;
import com.tarimatwasi.quipu.guest.application.port.out.GuestEmailAlreadyExistsException;
import com.tarimatwasi.quipu.guest.application.port.out.GuestRepositoryPort;
import com.tarimatwasi.quipu.guest.application.port.out.OnboardingRepositoryPort;
import com.tarimatwasi.quipu.guest.application.port.out.StayRepositoryPort;
import com.tarimatwasi.quipu.guest.domain.DocumentType;
import com.tarimatwasi.quipu.guest.domain.EmergencyContact;
import com.tarimatwasi.quipu.guest.domain.Guest;
import com.tarimatwasi.quipu.guest.domain.GuestStatus;
import com.tarimatwasi.quipu.guest.domain.GuestType;
import com.tarimatwasi.quipu.guest.domain.Stay;
import com.tarimatwasi.quipu.shared.debt.GuestDebtQuery;
import com.tarimatwasi.quipu.shared.debt.GuestHasPendingDebtException;
import com.tarimatwasi.quipu.shared.occupancy.ContractOccupancyQuery;
import com.tarimatwasi.quipu.shared.occupancy.Occupancy;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.CursorToken;
import com.tarimatwasi.quipu.shared.paging.InvalidCursorException;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@PreAuthorize("hasRole('ADMIN')") // BE-SPR-SEC-02 (TAR-170): admin use case, besides the URL rule
public class GuestService implements ManageGuestsUseCase, LockGuestUseCase {

  private static final Pattern DNI = Pattern.compile("[0-9]{8}");
  private static final Pattern OTHER_DOCUMENT = Pattern.compile("[A-Za-z0-9-]{1,20}");
  private static final AccountState NO_ACCOUNT = new AccountState(false, false);

  private final GuestRepositoryPort guests;
  private final GuestAccountPort accounts;
  private final StayRepositoryPort stays;
  private final ContractOccupancyQuery contracts;
  private final GuestDebtQuery debts;
  private final OnboardingRepositoryPort onboarding;
  private final Clock clock;

  GuestService(
      GuestRepositoryPort guests,
      GuestAccountPort accounts,
      StayRepositoryPort stays,
      ContractOccupancyQuery contracts,
      GuestDebtQuery debts,
      OnboardingRepositoryPort onboarding,
      Clock clock) {
    this.onboarding = onboarding;
    this.stays = stays;
    this.contracts = contracts;
    this.debts = debts;
    this.clock = clock;
    this.guests = guests;
    this.accounts = accounts;
  }

  @Override
  @Transactional(readOnly = true)
  public GuestListView list(
      StatusFilter filter, @Nullable GuestKind type, boolean withoutContract, PageQuery page) {
    List<GuestStatus> statuses = statuses(filter);
    GuestType guestType = type == null ? null : toType(type);
    CursorPage<Guest> found =
        withoutContract
            ? withoutContract(guests.findAllMatching(statuses, guestType), page)
            : guests.findAll(statuses, guestType, page);
    Set<Long> ids = new LinkedHashSet<>();
    Set<Long> pending = new LinkedHashSet<>();
    for (Guest guest : found.content()) {
      ids.add(guest.id());
      if (guest.status() == GuestStatus.PENDING_ACTIVATION) {
        pending.add(guest.id());
      }
    }
    Map<Long, AccountState> states = ids.isEmpty() ? Map.of() : accounts.stateOf(ids);
    Map<Long, Instant> expiries =
        pending.isEmpty() ? Map.of() : accounts.unusedAccessExpiries(pending);
    Map<Long, Occupancy> environments = environmentsOf(found.content());
    CursorPage<GuestRowView> rows =
        found.map(
            guest ->
                new GuestRowView(
                    summary(guest, states.getOrDefault(guest.id(), NO_ACCOUNT)),
                    environments.get(guest.id()),
                    expiries.get(guest.id())));
    return new GuestListView(
        rows,
        guests.countByStatus(GuestStatus.PENDING_ACTIVATION),
        guests.countByStatus(GuestStatus.ONBOARDING));
  }

  /**
   * A4.1 (TAR-237): the guests without a contract in force, paged here in the order of the listing
   * because the filter needs the contracts; the guests are few.
   */
  private CursorPage<Guest> withoutContract(List<Guest> matching, PageQuery page) {
    Set<Long> ids = new LinkedHashSet<>();
    matching.forEach(guest -> ids.add(guest.id()));
    Set<Long> withContract = ids.isEmpty() ? Set.of() : contracts.guestsWithContractInForce(ids);
    List<Guest> free =
        matching.stream().filter(guest -> !withContract.contains(guest.id())).toList();
    String after = page.after();
    int from = after == null ? 0 : firstAfter(free, after);
    List<Guest> rows = free.subList(from, Math.min(free.size(), from + page.limit() + 1));
    return CursorPage.of(rows, page.limit(), GuestService::cursorOf);
  }

  /** Where the page after the cursor starts in the ordered list. */
  static int firstAfter(List<Guest> ordered, String cursor) {
    List<String> key = CursorToken.decode(cursor, 2);
    try {
      DocumentType.valueOf(key.get(0));
    } catch (IllegalArgumentException unknownType) {
      throw new InvalidCursorException();
    }
    for (int i = 0; i < ordered.size(); i++) {
      Guest guest = ordered.get(i);
      if (guest.documentType().name().equals(key.get(0))
          && guest.documentNumber().equals(key.get(1))) {
        return i + 1;
      }
    }
    // The guest of the cursor left the list (it got a contract): the next one by the same order.
    for (int i = 0; i < ordered.size(); i++) {
      Guest guest = ordered.get(i);
      int byType = guest.documentType().name().compareTo(key.get(0));
      if (byType > 0 || (byType == 0 && guest.documentNumber().compareTo(key.get(1)) > 0)) {
        return i;
      }
    }
    return ordered.size();
  }

  private static String cursorOf(Guest guest) {
    return CursorToken.encode(guest.documentType().name(), guest.documentNumber());
  }

  /**
   * A3 (TAR-237): for each guest, the contract or stay in course today, else the next one, else,
   * for an inactive guest, the last one. A stay registered before TAR-237 has no environment and
   * does not count.
   */
  private Map<Long, Occupancy> environmentsOf(List<Guest> page) {
    if (page.isEmpty()) {
      return Map.of();
    }
    Set<Long> ids = new LinkedHashSet<>();
    page.forEach(guest -> ids.add(guest.id()));
    Map<Long, List<Occupancy>> byGuest = occupanciesOf(ids);
    LocalDate today = LocalDate.now(clock);
    Map<Long, Occupancy> chosen = new HashMap<>();
    for (Guest guest : page) {
      relevant(
              byGuest.getOrDefault(guest.id(), List.of()),
              today,
              guest.status() == GuestStatus.INACTIVE)
          .ifPresent(occupancy -> chosen.put(guest.id(), occupancy));
    }
    return chosen;
  }

  @Override
  @Transactional(readOnly = true)
  public Map<Long, GuestRef> refsOf(Collection<Long> ids, LocalDate from, LocalDate to) {
    if (ids.isEmpty()) {
      return Map.of();
    }
    Map<Long, List<Occupancy>> byGuest = occupanciesOf(ids);
    Map<Long, GuestRef> refs = new HashMap<>();
    for (Guest guest : guests.findAllByIds(ids)) {
      refs.put(
          guest.id(),
          new GuestRef(
              guest.id(),
              guest.fullName(),
              GuestKind.valueOf(guest.type().name()),
              inRange(byGuest.getOrDefault(guest.id(), List.of()), from, to).orElse(null)));
    }
    return refs;
  }

  /** The one that crosses the range and starts last, else the one that starts last of all. */
  static Optional<Occupancy> inRange(List<Occupancy> occupancies, LocalDate from, LocalDate to) {
    Comparator<Occupancy> byStart = Comparator.comparing(Occupancy::startDate);
    Optional<Occupancy> crossing =
        occupancies.stream().filter(o -> o.crosses(from, to)).max(byStart);
    return crossing.isPresent() ? crossing : occupancies.stream().max(byStart);
  }

  /** The contracts and the stays with an environment of those guests, by guest. */
  private Map<Long, List<Occupancy>> occupanciesOf(Collection<Long> ids) {
    Map<Long, List<Occupancy>> byGuest = new HashMap<>();
    for (Occupancy contract : contracts.occupanciesOfGuests(ids)) {
      byGuest.computeIfAbsent(contract.guestId(), key -> new ArrayList<>()).add(contract);
    }
    for (Stay stay : stays.findAllByGuests(ids)) {
      Long environment = stay.environmentId();
      if (environment != null) {
        byGuest
            .computeIfAbsent(stay.guestId(), key -> new ArrayList<>())
            .add(
                new Occupancy(
                    environment,
                    Occupancy.Kind.STAY,
                    stay.guestId(),
                    null,
                    stay.id(),
                    stay.startDate(),
                    stay.endDate()));
      }
    }
    return byGuest;
  }

  /** The one in course today, else the next one, else (with {@code includePast}) the last one. */
  static Optional<Occupancy> relevant(
      List<Occupancy> occupancies, LocalDate today, boolean includePast) {
    Comparator<Occupancy> byStart = Comparator.comparing(Occupancy::startDate);
    Optional<Occupancy> current = occupancies.stream().filter(o -> o.holdsOn(today)).min(byStart);
    if (current.isPresent()) {
      return current;
    }
    Optional<Occupancy> next =
        occupancies.stream().filter(o -> o.startDate().isAfter(today)).min(byStart);
    if (next.isPresent() || !includePast) {
      return next;
    }
    // Neither in course nor coming: every one ended before today, so each has an end date.
    return occupancies.stream()
        .filter(o -> o.endDate() != null)
        .max(
            Comparator.comparing((Occupancy o) -> Objects.requireNonNull(o.endDate()))
                .thenComparing(byStart));
  }

  @Override
  @Transactional(readOnly = true)
  public GuestDetailView get(Long id) {
    Guest guest = guests.findById(id).orElseThrow(GuestNotFoundException::new);
    AccountState account = stateOf(guest);
    Stay stay = Stay.relevantOn(stays.findAllByGuest(id), LocalDate.now(clock)).orElse(null);
    return new GuestDetailView(
        summary(guest, account),
        guests.versionOf(id),
        accounts.accountEmail(id).orElse(null),
        guest.phone(),
        guest.email(),
        emergencyContact(guest.emergencyContact()),
        stay == null ? null : stay.startDate(),
        stay == null ? null : stay.endDate(),
        stay == null ? null : stay.agreedAmount(),
        progress(guest, account),
        onboarding.countDocuments(id),
        accounts.accessExpiry(id).orElse(null));
  }

  @Override
  // TAR-204: the lock ends with the transaction of the caller; without one it would end at once.
  @Transactional(propagation = Propagation.MANDATORY)
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
    // TAR-276 (BE-SPR-DAT-13): the guest changed since the client loaded it.
    if (guests.versionOf(id) != command.version()) {
      throw new OptimisticLockingFailureException("The guest changed since it was loaded");
    }
    Guest changed = withDocument(current, command);
    try {
      Guest saved = guests.save(changed);
      if (saved.documentType() != current.documentType()
          || !saved.documentNumber().equals(current.documentNumber())) {
        // Under the lock of the guest: its account logs in with the corrected document.
        accounts.changeAccountDocument(id, saved.documentType().name(), saved.documentNumber());
      }
      return summary(saved, stateOf(saved));
    } catch (GuestDocumentAlreadyExistsException e) {
      throw new GuestDocumentTakenException(e);
    } catch (GuestEmailAlreadyExistsException e) {
      throw new GuestEmailTakenException(e);
    }
  }

  /**
   * The guest row stays locked until the commit (order: environment, guest, contract, payment), so
   * a contract loaded at the same time either sees the guest INACTIVE or is seen here as in force.
   */
  @Override
  @Transactional
  public void deactivate(Long id) {
    Guest guest = guests.findByIdForUpdate(id).orElseThrow(GuestNotFoundException::new);
    if (guest.status() == GuestStatus.INACTIVE) {
      return;
    }
    contracts
        .guestContractInForce(id)
        .ifPresent(
            contract -> {
              throw new GuestHasActiveContractException(contract);
            });
    guests.save(withStatus(guest, GuestStatus.INACTIVE, guest.status()));
    // Lock order: guest, account, accesses. An access handed before stops working.
    accounts.setActive(id, false);
    accounts.revokeAccess(id);
  }

  /** The debt is read under the lock of the guest, as the contracts and direct payments do. */
  @Override
  @Transactional
  public GuestSummaryView reactivate(Long id) {
    Guest guest = guests.findByIdForUpdate(id).orElseThrow(GuestNotFoundException::new);
    if (guest.status() != GuestStatus.INACTIVE) {
      return summary(guest, stateOf(guest));
    }
    List<YearMonth> owed = debts.owedPeriods(id);
    if (!owed.isEmpty()) {
      throw new GuestHasPendingDebtException(owed);
    }
    // This service always stores the previous state; an INACTIVE row without it (loaded by hand)
    // comes back pending activation, the state that asks for a new access.
    GuestStatus before =
        guest.statusBeforeInactive() == null
            ? GuestStatus.PENDING_ACTIVATION
            : guest.statusBeforeInactive();
    Guest saved = guests.save(withStatus(guest, before, null));
    // D-45.2a, known limit: this only enables the account again. The access revoked on the
    // deactivation is not handed back, so a guest that had not chosen its password yet cannot log
    // in until the administrator issues a new access.
    accounts.setActive(id, true);
    return summary(saved, stateOf(saved));
  }

  static Guest withStatus(
      Guest guest, GuestStatus status, @Nullable GuestStatus statusBeforeInactive) {
    return new Guest(
        guest.id(),
        guest.documentType(),
        guest.documentNumber(),
        guest.fullName(),
        guest.email(),
        guest.phone(),
        guest.type(),
        status,
        statusBeforeInactive,
        guest.personalDataCompletedAt(),
        guest.documentsStepCompletedAt(),
        guest.emergencyContact());
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
  static String documentNumber(DocumentType type, @Nullable String requested, String current) {
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

  static GuestSummaryView summary(Guest guest, AccountState account) {
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
