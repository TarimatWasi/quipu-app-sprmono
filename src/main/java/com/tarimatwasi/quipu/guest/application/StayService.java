package com.tarimatwasi.quipu.guest.application;

import com.tarimatwasi.quipu.guest.domain.Guest;
import com.tarimatwasi.quipu.guest.domain.GuestStatus;
import com.tarimatwasi.quipu.guest.domain.GuestType;
import com.tarimatwasi.quipu.guest.domain.Stay;
import com.tarimatwasi.quipu.guest.port.in.EmptyGuestUpdateException;
import com.tarimatwasi.quipu.guest.port.in.GuestNotActiveException;
import com.tarimatwasi.quipu.guest.port.in.GuestNotFoundException;
import com.tarimatwasi.quipu.guest.port.in.GuestNotTemporaryException;
import com.tarimatwasi.quipu.guest.port.in.InvalidGuestStayException;
import com.tarimatwasi.quipu.guest.port.in.ManageStaysUseCase;
import com.tarimatwasi.quipu.guest.port.in.StayNotFoundException;
import com.tarimatwasi.quipu.guest.port.in.StayOverlapException;
import com.tarimatwasi.quipu.guest.port.out.GuestRepositoryPort;
import com.tarimatwasi.quipu.guest.port.out.StayRepositoryPort;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import java.math.BigDecimal;
import java.time.LocalDate;
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

  StayService(GuestRepositoryPort guests, StayRepositoryPort stays) {
    this.guests = guests;
    this.stays = stays;
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
  @Transactional
  public StayView create(Long guestId, NewStay stay) {
    validate(stay.startDate(), stay.endDate(), stay.agreedAmount());
    Guest guest = lockedGuest(guestId);
    if (guest.type() != GuestType.TEMPORARY) {
      throw new GuestNotTemporaryException();
    }
    if (guest.status() != GuestStatus.ACTIVE) {
      throw new GuestNotActiveException(guestId);
    }
    rejectOverlap(guestId, NO_STAY, stay.startDate(), stay.endDate());
    return view(stays.insert(guestId, stay.startDate(), stay.endDate(), stay.agreedAmount()));
  }

  @Override
  @Transactional
  public StayView update(Long guestId, Long stayId, StayChange change) {
    if (change.startDate() == null && change.endDate() == null && change.agreedAmount() == null) {
      throw new EmptyGuestUpdateException();
    }
    lockedGuest(guestId);
    Stay current = stays.findByIdAndGuest(stayId, guestId).orElseThrow(StayNotFoundException::new);
    LocalDate start = change.startDate() == null ? current.startDate() : change.startDate();
    LocalDate end = change.endDate() == null ? current.endDate() : change.endDate();
    BigDecimal amount =
        change.agreedAmount() == null ? current.agreedAmount() : change.agreedAmount();
    validate(start, end, amount);
    rejectOverlap(guestId, stayId, start, end);
    return view(stays.update(new Stay(stayId, guestId, start, end, amount)));
  }

  /**
   * The guest row stays locked until the commit, so two simultaneous writes of the stays of one
   * guest are checked for overlap one after the other.
   */
  private Guest lockedGuest(Long guestId) {
    return guests.findByIdForUpdate(guestId).orElseThrow(GuestNotFoundException::new);
  }

  /** The dates may not cross any stay of the guest other than the one being corrected. */
  private void rejectOverlap(Long guestId, Long ownStayId, LocalDate start, LocalDate end) {
    boolean overlaps =
        stays.findAllByGuest(guestId).stream()
            .filter(other -> !other.id().equals(ownStayId))
            .anyMatch(other -> other.overlaps(start, end));
    if (overlaps) {
      throw new StayOverlapException();
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
    return new StayView(stay.id(), stay.startDate(), stay.endDate(), stay.agreedAmount());
  }
}
