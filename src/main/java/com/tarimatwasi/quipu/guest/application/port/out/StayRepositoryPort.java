package com.tarimatwasi.quipu.guest.application.port.out;

import com.tarimatwasi.quipu.guest.domain.Stay;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.InvalidCursorException;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import java.math.BigDecimal;
import java.time.LocalDate;
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

  /** Stores a new stay. */
  Stay insert(Long guestId, LocalDate startDate, LocalDate endDate, BigDecimal agreedAmount);

  /** Stores the new data of an existing stay. */
  Stay update(Stay stay);

  /**
   * The stays of any guest in course on that day (the dates are inclusive), the lowest id first.
   */
  List<Stay> findAllInForceOn(LocalDate day);

  /** The ids of the stays of any guest that start between both days, inclusive. */
  List<Long> findIdsStartingBetween(LocalDate first, LocalDate last);
}
