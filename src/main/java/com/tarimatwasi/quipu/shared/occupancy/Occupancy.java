package com.tarimatwasi.quipu.shared.occupancy;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * TAR-237 (RN-20): a contract or a stay that occupies an environment during a range of days. A
 * contract finished by hand (RN-21) ends on the day it was finished.
 *
 * @param contractId set when the kind is CONTRACT
 * @param stayId set when the kind is STAY
 * @param endDate the last day; null for a contract without end date
 */
public record Occupancy(
    Long environmentId,
    Kind kind,
    Long guestId,
    @Nullable Long contractId,
    @Nullable Long stayId,
    LocalDate startDate,
    @Nullable LocalDate endDate) {

  /** What occupies the environment. */
  public enum Kind {
    CONTRACT,
    STAY
  }

  /** Whether it holds the environment on that day. */
  public boolean holdsOn(LocalDate day) {
    return !startDate.isAfter(day) && (endDate == null || !endDate.isBefore(day));
  }

  /** Whether it holds the environment on at least one day of the range (null end: open). */
  public boolean crosses(LocalDate from, @Nullable LocalDate to) {
    boolean reachesFrom = endDate == null || !endDate.isBefore(from);
    boolean startsByTo = to == null || !startDate.isAfter(to);
    return reachesFrom && startsByTo;
  }
}
