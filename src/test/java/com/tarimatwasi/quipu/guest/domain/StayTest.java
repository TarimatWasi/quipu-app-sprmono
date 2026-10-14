package com.tarimatwasi.quipu.guest.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class StayTest {

  private static final LocalDate TODAY = LocalDate.parse("2026-10-13");

  private static Stay stay(long id, String start, String end) {
    return new Stay(id, 1L, LocalDate.parse(start), LocalDate.parse(end), BigDecimal.TEN);
  }

  @Test
  void aStayInCourseTodayIsPreferredToTheNextAndToThePast() {
    var past = stay(1, "2026-09-01", "2026-09-10");
    var inCourse = stay(2, "2026-10-10", "2026-10-20");
    var next = stay(3, "2026-11-01", "2026-11-05");

    assertThat(Stay.relevantOn(List.of(past, next, inCourse), TODAY)).contains(inCourse);
  }

  @Test
  void theFirstAndLastDaysCountAsInCourse() {
    var endsToday = stay(1, "2026-10-01", "2026-10-13");
    var startsToday = stay(2, "2026-10-13", "2026-10-20");

    assertThat(Stay.relevantOn(List.of(endsToday), TODAY)).contains(endsToday);
    assertThat(Stay.relevantOn(List.of(startsToday), TODAY)).contains(startsToday);
  }

  @Test
  void withNoneInCourseTheEarliestFutureOneWins() {
    var past = stay(1, "2026-09-01", "2026-09-10");
    var later = stay(2, "2026-12-01", "2026-12-05");
    var soon = stay(3, "2026-11-01", "2026-11-05");

    assertThat(Stay.relevantOn(List.of(past, later, soon), TODAY)).contains(soon);
  }

  @Test
  void withOnlyPastStaysTheOneThatEndedLastWins() {
    var old = stay(1, "2026-01-01", "2026-01-10");
    var recent = stay(2, "2026-08-01", "2026-08-10");

    assertThat(Stay.relevantOn(List.of(recent, old), TODAY)).contains(recent);
  }

  @Test
  void ties_areBrokenByTheLowestId() {
    var first = stay(5, "2026-11-01", "2026-11-05");
    var second = stay(9, "2026-11-01", "2026-11-05");
    var endedTogetherA = stay(2, "2026-01-01", "2026-01-10");
    var endedTogetherB = stay(1, "2026-01-05", "2026-01-10");

    assertThat(Stay.relevantOn(List.of(second, first), TODAY)).contains(first);
    assertThat(Stay.relevantOn(List.of(endedTogetherA, endedTogetherB), TODAY))
        .contains(endedTogetherB);
  }

  @Test
  void noStaysMeansNoRelevantStay() {
    assertThat(Stay.relevantOn(List.of(), TODAY)).isEmpty();
  }

  @Test
  void overlapIsInclusiveOnBothEnds() {
    var stay = stay(1, "2026-10-10", "2026-10-20");

    assertThat(stay.overlaps(day("2026-10-20"), day("2026-10-25"))).isTrue();
    assertThat(stay.overlaps(day("2026-10-01"), day("2026-10-10"))).isTrue();
    assertThat(stay.overlaps(day("2026-10-12"), day("2026-10-14"))).isTrue();
    assertThat(stay.overlaps(day("2026-10-21"), day("2026-10-25"))).isFalse();
    assertThat(stay.overlaps(day("2026-10-01"), day("2026-10-09"))).isFalse();
  }

  private static LocalDate day(String date) {
    return LocalDate.parse(date);
  }
}
