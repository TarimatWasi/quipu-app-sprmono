package com.tarimatwasi.quipu.environment.application.service;

import com.tarimatwasi.quipu.shared.occupancy.Occupancy;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * TAR-237 (RN-20, A2): what the contracts and the stays of one environment say about it on a day.
 * The occupancy is derived on every read (QP-SPRMONO-TIM-02), never stored.
 *
 * @param occupancies every contract and stay of the environment
 */
record EnvironmentOccupancies(List<Occupancy> occupancies) {

  private static final Comparator<Occupancy> BY_START =
      Comparator.comparing(Occupancy::startDate)
          .thenComparing(occupancy -> occupancy.kind().name())
          .thenComparing(EnvironmentOccupancies::idOf);

  /** Who holds it that day: a contract in force or a stay in course; the earliest start first. */
  Optional<Occupancy> occupantOn(LocalDate day) {
    return occupancies.stream().filter(occupancy -> occupancy.holdsOn(day)).min(BY_START);
  }

  /** The next one: a contract or a stay that starts after that day (A2 «Libre hasta …»). */
  Optional<Occupancy> nextAfter(LocalDate day) {
    return occupancies.stream()
        .filter(occupancy -> occupancy.startDate().isAfter(day))
        .min(BY_START);
  }

  /**
   * When it became free: the day after the last contract or stay that ended before that day, or the
   * day it was created when nothing ever occupied it. Null while it is occupied.
   */
  @Nullable LocalDate freeSince(LocalDate day, LocalDate createdOn) {
    if (occupantOn(day).isPresent()) {
      return null;
    }
    return occupancies.stream()
        .map(Occupancy::endDate)
        .filter(end -> end != null && end.isBefore(day))
        .map(end -> end.plusDays(1))
        .max(Comparator.naturalOrder())
        .orElse(createdOn);
  }

  /** Whether nothing holds it on any day of the range (a null end: open-ended). */
  boolean freeBetween(LocalDate from, @Nullable LocalDate to) {
    return occupancies.stream().noneMatch(occupancy -> occupancy.crosses(from, to));
  }

  /** Free that day and with nothing coming: what the A2 notice counts. */
  boolean freeWithNothingComing(LocalDate day) {
    return occupantOn(day).isEmpty() && nextAfter(day).isEmpty();
  }

  private static Long idOf(Occupancy occupancy) {
    Long contract = occupancy.contractId();
    Long stay = occupancy.stayId();
    return contract != null ? contract : stay != null ? stay : 0L;
  }
}
