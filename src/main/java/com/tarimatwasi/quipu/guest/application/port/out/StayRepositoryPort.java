package com.tarimatwasi.quipu.guest.application.port.out;

import com.tarimatwasi.quipu.guest.domain.Stay;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.InvalidCursorException;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StayRepositoryPort {

  /**
   * One page of the stays of the guest, newest start first.
   *
   * @throws InvalidCursorException if the cursor of the page is not one this service issued
   */
  CursorPage<Stay> findByGuest(Long guestId, PageQuery page);

  /** Every stay of the guest. */
  List<Stay> findAllByGuest(Long guestId);

  /** The stay, only if it belongs to that guest. */
  Optional<Stay> findByIdAndGuest(Long id, Long guestId);

  /** Stores a new stay in the environment it occupies (RN-20, TAR-237). */
  Stay insert(
      Long guestId,
      Long environmentId,
      LocalDate startDate,
      LocalDate endDate,
      BigDecimal agreedAmount);

  /**
   * TAR-237 (RN-20): whether a stay of the environment, other than the one excluded, shares at
   * least one day with the range.
   *
   * @param excludedStayId the stay being corrected, which does not take its own environment; {@code
   *     0L} for none
   */
  boolean environmentTaken(Long environmentId, LocalDate start, LocalDate end, Long excludedStayId);

  /** TAR-237: whether a stay of the environment ends on that day or later (open-ended ranges). */
  boolean environmentTakenFrom(Long environmentId, LocalDate start);

  /** TAR-237 (A2): every stay of those environments. */
  List<Stay> findAllByEnvironments(Collection<Long> environmentIds);

  /** Stores the new data of an existing stay. */
  Stay update(Stay stay);

  /**
   * The stays of any guest in course on that day (the dates are inclusive), the lowest id first.
   */
  List<Stay> findAllInForceOn(LocalDate day);

  /** The ids of the stays of any guest that start between both days, inclusive. */
  List<Long> findIdsStartingBetween(LocalDate first, LocalDate last);
}
