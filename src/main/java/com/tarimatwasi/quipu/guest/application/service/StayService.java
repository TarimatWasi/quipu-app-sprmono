package com.tarimatwasi.quipu.guest.application.service;

import com.tarimatwasi.quipu.environment.application.port.in.EnvironmentOccupiedException;
import com.tarimatwasi.quipu.guest.application.port.in.EmptyGuestUpdateException;
import com.tarimatwasi.quipu.guest.application.port.in.GuestNotActiveException;
import com.tarimatwasi.quipu.guest.application.port.in.GuestNotFoundException;
import com.tarimatwasi.quipu.guest.application.port.in.GuestNotTemporaryException;
import com.tarimatwasi.quipu.guest.application.port.in.InvalidGuestStayException;
import com.tarimatwasi.quipu.guest.application.port.in.ManageStaysUseCase;
import com.tarimatwasi.quipu.guest.application.port.in.StayNotFoundException;
import com.tarimatwasi.quipu.guest.application.port.in.StayOverlapException;
import com.tarimatwasi.quipu.guest.application.port.out.GuestRepositoryPort;
import com.tarimatwasi.quipu.guest.application.port.out.StayEnvironmentPort;
import com.tarimatwasi.quipu.guest.application.port.out.StayRepositoryPort;
import com.tarimatwasi.quipu.guest.domain.Guest;
import com.tarimatwasi.quipu.guest.domain.GuestStatus;
import com.tarimatwasi.quipu.guest.domain.GuestType;
import com.tarimatwasi.quipu.guest.domain.Stay;
import com.tarimatwasi.quipu.shared.occupancy.ContractOccupancyQuery;
import com.tarimatwasi.quipu.shared.occupancy.Occupancy;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@PreAuthorize("hasRole('ADMIN')") // BE-SPR-SEC-02 (TAR-170): admin use case, besides the URL rule
public class StayService implements ManageStaysUseCase {

  /** NUMERIC(10,2): eight digits before the point. */
  private static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999.99");

  /** The years a PostgreSQL date and a cursor can carry without a surprise. */
  private static final int MIN_YEAR = 1900;

  private static final int MAX_YEAR = 9999;

  /** The id that no stay has, for a stay that is not stored yet. */
  private static final Long NO_STAY = -1L;

  private final GuestRepositoryPort guests;
  private final StayRepositoryPort stays;
  private final StayEnvironmentPort environments;
  private final ContractOccupancyQuery contracts;

  StayService(
      GuestRepositoryPort guests,
      StayRepositoryPort stays,
      StayEnvironmentPort environments,
      ContractOccupancyQuery contracts) {
    this.guests = guests;
    this.stays = stays;
    this.environments = environments;
    this.contracts = contracts;
  }

  @Override
  @Transactional(readOnly = true)
  public CursorPage<StayView> list(Long guestId, PageQuery page) {
    if (guests.findById(guestId).isEmpty()) {
      throw new GuestNotFoundException();
    }
    return stays.findByGuest(guestId, page).map(StayService::view);
  }

  @Override
  @Transactional(readOnly = true)
  public StayView get(Long guestId, Long stayId) {
    return stays
        .findByIdAndGuest(stayId, guestId)
        .map(StayService::view)
        .orElseThrow(StayNotFoundException::new);
  }

  @Override
  @Transactional(readOnly = true)
  public List<Long> idsStartingIn(YearMonth month) {
    return stays.findIdsStartingBetween(month.atDay(1), month.atEndOfMonth());
  }

  @Override
  @Transactional(readOnly = true)
  public List<StayView> inForceOn(LocalDate day) {
    Set<Long> seen = new HashSet<>();
    // Read the lowest id first, so the first stay of each guest is the one kept.
    return stays.findAllInForceOn(day).stream()
        .filter(stay -> seen.add(stay.guestId()))
        .map(StayService::view)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<StayView> all() {
    return stays.findEvery().stream().map(StayService::view).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<StayView> allOf(Long guestId) {
    return stays.findAllByGuest(guestId).stream().map(StayService::view).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<StayView> byIds(Collection<Long> ids) {
    return ids.isEmpty()
        ? List.of()
        : stays.findAllByIds(ids).stream().map(StayService::view).toList();
  }

  @Override
  @Transactional
  public StayView create(Long guestId, NewStay stay) {
    validate(stay.startDate(), stay.endDate(), stay.agreedAmount());
    // TAR-237: the global lock order is environment, guest, contract, payment.
    boolean environmentActive = environments.lock(stay.environmentId());
    Guest guest = lockedGuest(guestId);
    if (guest.type() != GuestType.TEMPORARY) {
      throw new GuestNotTemporaryException();
    }
    if (guest.status() != GuestStatus.ACTIVE) {
      throw new GuestNotActiveException(guestId);
    }
    rejectOverlap(guestId, NO_STAY, stay.startDate(), stay.endDate());
    rejectTaken(environmentActive, stay.environmentId(), stay.startDate(), stay.endDate(), NO_STAY);
    return view(
        stays.insert(
            guestId, stay.environmentId(), stay.startDate(), stay.endDate(), stay.agreedAmount()));
  }

  @Override
  @Transactional
  public StayView update(Long guestId, Long stayId, StayChange change) {
    if (change.environmentId() == null
        && change.startDate() == null
        && change.endDate() == null
        && change.agreedAmount() == null) {
      throw new EmptyGuestUpdateException();
    }
    // TAR-237: the environment is locked before the guest (global lock order). Which one: the new
    // one, or the one the stay already has, read without lock and read again under it below.
    Long environmentId =
        change.environmentId() != null
            ? change.environmentId()
            : stays.findByIdAndGuest(stayId, guestId).map(Stay::environmentId).orElse(null);
    boolean environmentActive = environmentId == null || environments.lock(environmentId);
    lockedGuest(guestId);
    Stay current = stays.findByIdAndGuest(stayId, guestId).orElseThrow(StayNotFoundException::new);
    // TAR-276 (BE-SPR-DAT-13): the stay changed since the client loaded it; nothing is saved.
    if (current.version() != change.version()) {
      throw new OptimisticLockingFailureException("The stay changed since it was loaded");
    }
    LocalDate start = change.startDate() == null ? current.startDate() : change.startDate();
    LocalDate end = change.endDate() == null ? current.endDate() : change.endDate();
    BigDecimal amount =
        change.agreedAmount() == null ? current.agreedAmount() : change.agreedAmount();
    Long target = change.environmentId() != null ? change.environmentId() : current.environmentId();
    validate(start, end, amount);
    rejectOverlap(guestId, stayId, start, end);
    // A stay registered before TAR-237 keeps «Sin ambiente» until one is chosen for it. Only a
    // change of environment or dates can take an environment held by someone else.
    boolean moves =
        !Objects.equals(target, current.environmentId())
            || !start.equals(current.startDate())
            || !end.equals(current.endDate());
    if (target != null && moves) {
      rejectTaken(target.equals(environmentId) && environmentActive, target, start, end, stayId);
    }
    return view(
        stays.update(new Stay(stayId, guestId, start, end, amount, target, current.version())));
  }

  /**
   * TAR-237 (RN-20): the environment must be active and free in those dates: no contract not
   * terminated and no other stay holds it on any of them.
   */
  private void rejectTaken(
      boolean environmentActive,
      Long environmentId,
      LocalDate start,
      LocalDate end,
      Long ownStayId) {
    if (!environmentActive) {
      throw EnvironmentOccupiedException.forStay(null);
    }
    if (contracts.environmentTaken(environmentId, start, end)) {
      throw EnvironmentOccupiedException.forStay(
          contracts.contractCrossing(environmentId, start, end).orElse(null));
    }
    if (stays.environmentTaken(environmentId, start, end, ownStayId)) {
      throw EnvironmentOccupiedException.forStay(
          otherStayCrossing(environmentId, start, end, ownStayId).orElse(null));
    }
  }

  /** TAR-237: the other stay that holds the environment in those dates, the one starting first. */
  private Optional<Occupancy> otherStayCrossing(
      Long environmentId, LocalDate start, LocalDate end, Long ownStayId) {
    List<Occupancy> others =
        stays.findAllByEnvironments(List.of(environmentId)).stream()
            .filter(stay -> !stay.id().equals(ownStayId))
            .map(
                stay ->
                    new Occupancy(
                        environmentId,
                        Occupancy.Kind.STAY,
                        stay.guestId(),
                        null,
                        stay.id(),
                        stay.startDate(),
                        stay.endDate()))
            .toList();
    return Occupancy.firstCrossing(others, start, end);
  }

  /**
   * The guest row stays locked until the commit, so two simultaneous writes of the stays of one
   * guest are checked for overlap one after the other.
   */
  private Guest lockedGuest(Long guestId) {
    return guests.findByIdForUpdate(guestId).orElseThrow(GuestNotFoundException::new);
  }

  /**
   * The dates may not cross any stay of the guest other than the one being corrected; the error
   * names the crossed stay that starts first.
   */
  private void rejectOverlap(Long guestId, Long ownStayId, LocalDate start, LocalDate end) {
    Optional<Stay> crossed =
        stays.findAllByGuest(guestId).stream()
            .filter(other -> !other.id().equals(ownStayId))
            .filter(other -> other.overlaps(start, end))
            .min(Comparator.comparing(Stay::startDate).thenComparing(Stay::id));
    if (crossed.isPresent()) {
      Stay first = crossed.get();
      throw new StayOverlapException(first.startDate(), first.endDate());
    }
  }

  private static void validate(LocalDate start, LocalDate end, BigDecimal amount) {
    if (outOfRange(start)) {
      throw new InvalidGuestStayException("startDate");
    }
    if (outOfRange(end) || end.isBefore(start)) {
      throw new InvalidGuestStayException("endDate");
    }
    if (!validAmount(amount)) {
      throw new InvalidGuestStayException("agreedAmount");
    }
  }

  private static boolean outOfRange(LocalDate date) {
    return date.getYear() < MIN_YEAR || date.getYear() > MAX_YEAR;
  }

  /** Greater than 0, at most two decimals, and it fits NUMERIC(10,2). */
  private static boolean validAmount(BigDecimal amount) {
    return amount.signum() > 0
        && amount.stripTrailingZeros().scale() <= 2
        && amount.compareTo(MAX_AMOUNT) <= 0;
  }

  private static StayView view(Stay stay) {
    return new StayView(
        stay.id(),
        stay.guestId(),
        stay.startDate(),
        stay.endDate(),
        stay.agreedAmount(),
        stay.environmentId(),
        stay.version());
  }
}
