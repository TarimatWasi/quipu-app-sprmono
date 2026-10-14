package com.tarimatwasi.quipu.guest.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * One visit of a temporary guest (RN-13). The dates are inclusive on both ends.
 *
 * @param guestId the guest it belongs to
 */
public record Stay(
    Long id, Long guestId, LocalDate startDate, LocalDate endDate, BigDecimal agreedAmount) {

  /** Whether the two stays share at least one day. */
  public boolean overlaps(LocalDate otherStart, LocalDate otherEnd) {
    return !startDate.isAfter(otherEnd) && !endDate.isBefore(otherStart);
  }

  /**
   * The stay a guest summary shows: the one in course today, else the next one (the earliest in the
   * future), else the latest (the most recent end). Ties are broken by id.
   */
  public static Optional<Stay> relevantOn(List<Stay> stays, LocalDate today) {
    Comparator<Stay> byId = Comparator.comparing(Stay::id);
    return stays.stream()
        .filter(stay -> !stay.startDate().isAfter(today) && !stay.endDate().isBefore(today))
        .min(byId)
        .or(
            () ->
                stays.stream()
                    .filter(stay -> stay.startDate().isAfter(today))
                    .min(Comparator.comparing(Stay::startDate).thenComparing(byId)))
        .or(
            () ->
                stays.stream()
                    .max(Comparator.comparing(Stay::endDate).thenComparing(byId.reversed())));
  }
}
