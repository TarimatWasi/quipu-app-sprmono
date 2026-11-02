package com.tarimatwasi.quipu.environment.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.shared.occupancy.Occupancy;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** TAR-237 (RN-20, A2): who holds an environment, who comes next and since when it is free. */
class EnvironmentOccupanciesTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 11, 1);
  private static final LocalDate CREATED = LocalDate.of(2026, 1, 1);

  private static Occupancy contract(long id, String start, @Nullable String end) {
    return new Occupancy(
        1L,
        Occupancy.Kind.CONTRACT,
        10L,
        id,
        null,
        LocalDate.parse(start),
        end == null ? null : LocalDate.parse(end));
  }

  private static Occupancy stay(long id, String start, String end) {
    return new Occupancy(
        1L, Occupancy.Kind.STAY, 20L, null, id, LocalDate.parse(start), LocalDate.parse(end));
  }

  @Test
  void occupantOn_isTheOneThatHoldsTheDay_andFreeSinceIsNull() {
    var occupancies =
        new EnvironmentOccupancies(
            List.of(contract(1, "2026-10-01", null), stay(2, "2027-01-01", "2027-01-05")));

    assertThat(occupancies.occupantOn(TODAY)).map(Occupancy::contractId).contains(1L);
    assertThat(occupancies.nextAfter(TODAY)).map(Occupancy::stayId).contains(2L);
    assertThat(occupancies.freeSince(TODAY, CREATED)).isNull();
    assertThat(occupancies.freeWithNothingComing(TODAY)).isFalse();
  }

  @Test
  void freeSince_isTheDayAfterTheLastEnd_orTheCreationWhenNothingEverHeldIt() {
    var ended =
        new EnvironmentOccupancies(
            List.of(contract(1, "2026-01-01", "2026-06-30"), stay(2, "2026-09-10", "2026-09-20")));

    assertThat(ended.occupantOn(TODAY)).isEmpty();
    assertThat(ended.freeSince(TODAY, CREATED)).isEqualTo(LocalDate.of(2026, 9, 21));
    assertThat(ended.freeWithNothingComing(TODAY)).isTrue();
    assertThat(new EnvironmentOccupancies(List.of()).freeSince(TODAY, CREATED)).isEqualTo(CREATED);
  }

  @Test
  void freeBetween_refusesAnyCrossing_andAnOpenEndedRangeReachesALaterStay() {
    var occupancies = new EnvironmentOccupancies(List.of(stay(2, "2026-12-10", "2026-12-20")));

    assertThat(occupancies.freeBetween(LocalDate.of(2026, 11, 5), LocalDate.of(2026, 12, 9)))
        .isTrue();
    assertThat(occupancies.freeBetween(LocalDate.of(2026, 12, 20), LocalDate.of(2026, 12, 25)))
        .isFalse();
    assertThat(occupancies.freeBetween(LocalDate.of(2026, 11, 5), null)).isFalse();
  }
}
