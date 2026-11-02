package com.tarimatwasi.quipu.guest.application.port.in;

import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.InvalidCursorException;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** RN-13, RN-17, CU-31: the stays of a temporary guest. A stay is never deleted. */
public interface ManageStaysUseCase {

  /**
   * A stay as the listings show it.
   *
   * @param environmentId the environment it occupies (TAR-237); null for a stay registered before
   * @param version the optimistic-locking version the edit sends back (BE-SPR-DAT-13, TAR-276)
   */
  record StayView(
      Long id,
      LocalDate startDate,
      LocalDate endDate,
      BigDecimal agreedAmount,
      @Nullable Long environmentId,
      long version) {}

  /** The data of a new stay, with the environment it occupies (RN-20, TAR-237). */
  record NewStay(
      Long environmentId, LocalDate startDate, LocalDate endDate, BigDecimal agreedAmount) {}

  /**
   * What changes in a stay; only the present fields change (the environment too, TAR-237).
   *
   * @param version the version the client loaded; a different one is a concurrent change and
   *     nothing is saved (BE-SPR-DAT-13, TAR-276)
   */
  record StayChange(
      long version,
      @Nullable Long environmentId,
      @Nullable LocalDate startDate,
      @Nullable LocalDate endDate,
      @Nullable BigDecimal agreedAmount) {}

  /**
   * One page of the stays of the guest, newest first.
   *
   * @throws GuestNotFoundException if there is no guest with that id
   * @throws InvalidCursorException if the cursor of the page is not one this service issued
   */
  CursorPage<StayView> list(Long guestId, PageQuery page);

  /**
   * Registers a stay.
   *
   * @throws GuestNotFoundException if there is no guest with that id
   * @throws GuestNotTemporaryException if the guest is not temporary
   * @throws GuestNotActiveException if the guest is not active
   * @throws InvalidGuestStayException if the dates are out of order or the amount is not valid
   * @throws StayOverlapException if the dates cross another stay of the guest
   * @throws com.tarimatwasi.quipu.environment.application.port.in.EnvironmentOccupiedException if
   *     the environment is inactive or taken in those dates by a contract or another stay
   * @throws com.tarimatwasi.quipu.environment.application.port.in.EnvironmentNotFoundException if
   *     there is no environment with that id
   */
  StayView create(Long guestId, NewStay stay);

  /**
   * Corrects the present fields of a stay.
   *
   * @throws GuestNotFoundException if there is no guest with that id
   * @throws StayNotFoundException if the guest has no stay with that id
   * @throws EmptyGuestUpdateException if no field is present
   * @throws InvalidGuestStayException if the resulting dates or amount are not valid
   * @throws StayOverlapException if the resulting dates cross another stay of the guest
   * @throws com.tarimatwasi.quipu.environment.application.port.in.EnvironmentOccupiedException if
   *     the resulting environment is inactive or taken in the resulting dates
   */
  StayView update(Long guestId, Long stayId, StayChange change);

  /**
   * One stay of the guest.
   *
   * @throws StayNotFoundException if the guest has no stay with that id
   */
  StayView get(Long guestId, Long stayId);

  /**
   * The ids of the stays of any guest, active or inactive, that start in the month (RF-18): the
   * month its payment counts in.
   */
  List<Long> idsStartingIn(YearMonth month);

  /**
   * HU-A04: the stay in course on that day of each guest, active or inactive, in one read. The
   * stays of a guest never overlap; should two be found anyway, the lowest id is kept.
   */
  List<StayView> inForceOn(LocalDate day);

  /** TAR-239 (A5): those stays, of any guest; an id with no stay adds nothing. */
  List<StayView> byIds(Collection<Long> ids);
}
