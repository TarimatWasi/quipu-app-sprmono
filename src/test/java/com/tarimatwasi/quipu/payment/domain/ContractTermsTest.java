package com.tarimatwasi.quipu.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** RN-02, RN-06, RN-07, RN-10: the periods of a contract and the state of each, on a given day. */
class ContractTermsTest {

  private static final LocalDate TODAY = LocalDate.parse("2026-10-14");

  private static ContractTerms terms(
      String start, @Nullable String end, @Nullable YearMonth terminatedIn, int day, int grace) {
    return new ContractTerms(
        LocalDate.parse(start),
        end == null ? null : LocalDate.parse(end),
        terminatedIn,
        day,
        grace);
  }

  private static YearMonth ym(String value) {
    return YearMonth.parse(value);
  }

  private static Payment payment(
      long id, String period, PaymentStatus status, @Nullable String reason) {
    return new Payment(
        id, 1L, 10L, null, ym(period), BigDecimal.TEN, null, status, reason, null, null);
  }

  // --- the periods ---

  @Test
  void aContractChargesFromItsStartMonthUpToTheCurrentOne() {
    assertThat(terms("2026-08-20", null, null, 5, 3).periods(TODAY))
        .containsExactly(ym("2026-08"), ym("2026-09"), ym("2026-10"));
  }

  @Test
  void theStartAndEndMonthsAreWholePeriodsWithoutProrating() {
    assertThat(terms("2026-10-14", "2026-10-15", null, 5, 3).periods(TODAY))
        .containsExactly(ym("2026-10"));
    assertThat(terms("2026-09-30", null, null, 5, 3).periods(TODAY))
        .containsExactly(ym("2026-09"), ym("2026-10"));
  }

  @Test
  void aContractThatStartsOnTheLastDayOfTheMonthChargesThatMonth() {
    assertThat(terms("2026-10-31", null, null, 5, 3).periods(LocalDate.parse("2026-10-31")))
        .containsExactly(ym("2026-10"));
    assertThat(terms("2026-10-31", null, null, 5, 3).periods(LocalDate.parse("2026-11-01")))
        .containsExactly(ym("2026-10"), ym("2026-11"));
  }

  @Test
  void aContractThatStartsInALaterMonthChargesNothingYet() {
    assertThat(terms("2026-11-01", null, null, 5, 3).periods(TODAY)).isEmpty();
    assertThat(terms("2026-12-15", "2027-12-15", null, 5, 3).periods(TODAY)).isEmpty();
  }

  @Test
  void anExpiredContractStopsAtTheMonthOfItsEndDate() {
    assertThat(terms("2026-06-01", "2026-08-31", null, 5, 3).periods(TODAY))
        .containsExactly(ym("2026-06"), ym("2026-07"), ym("2026-08"));
    assertThat(terms("2026-06-01", "2026-08-01", null, 5, 3).periods(TODAY)).hasSize(3);
  }

  @Test
  void aContractFinishedByHandStopsAtTheMonthOfTheTermination() {
    assertThat(terms("2026-06-01", null, ym("2026-08"), 5, 3).periods(TODAY))
        .containsExactly(ym("2026-06"), ym("2026-07"), ym("2026-08"));
  }

  @Test
  void aContractFinishedInTheMiddleOfTheMonthStillChargesThatWholeMonth() {
    assertThat(
            terms("2026-06-01", null, ym("2026-10"), 5, 3).periods(LocalDate.parse("2026-10-15")))
        .endsWith(ym("2026-10"));
  }

  @Test
  void theEarliestOfTheEndAndTheTerminationWins() {
    assertThat(terms("2026-06-01", "2026-09-30", ym("2026-07"), 5, 3).periods(TODAY))
        .containsExactly(ym("2026-06"), ym("2026-07"));
    assertThat(terms("2026-06-01", "2026-07-30", ym("2026-09"), 5, 3).periods(TODAY))
        .containsExactly(ym("2026-06"), ym("2026-07"));
  }

  @Test
  void aContractFinishedBeforeItsFirstMonthChargesNothing() {
    assertThat(terms("2026-10-01", null, ym("2026-09"), 5, 3).periods(TODAY)).isEmpty();
  }

  @Test
  void theMonthChangesWithTheDayOfLimaAndNotWithUtc() {
    // 2026-11-01 03:00 UTC is still 2026-10-31 22:00 in Lima
    Clock clock = Clock.fixed(Instant.parse("2026-11-01T03:00:00Z"), ZoneId.of("America/Lima"));
    Clock utc = Clock.fixed(Instant.parse("2026-11-01T03:00:00Z"), ZoneOffset.UTC);
    ContractTerms contract = terms("2026-09-01", null, null, 5, 3);

    assertThat(contract.periods(BusinessDay.today(clock)))
        .containsExactly(ym("2026-09"), ym("2026-10"));
    assertThat(contract.periods(BusinessDay.today(utc)))
        .containsExactly(ym("2026-09"), ym("2026-10"), ym("2026-11"));
  }

  @Test
  void theTerminationMonthIsTheOneOfTheInstantInLimaAndNotInUtc() {
    // 2026-11-01 03:00 UTC is 2026-10-31 22:00 in Lima: the contract was finished in October
    Instant finishedAt = Instant.parse("2026-11-01T03:00:00Z");
    LocalDate today = LocalDate.parse("2026-12-10");

    var inLima =
        ContractTerms.of(
            LocalDate.parse("2026-09-01"), null, finishedAt, ZoneId.of("America/Lima"), 5, 3);
    var inUtc =
        ContractTerms.of(LocalDate.parse("2026-09-01"), null, finishedAt, ZoneOffset.UTC, 5, 3);

    assertThat(inLima.terminatedIn()).isEqualTo(ym("2026-10"));
    assertThat(inLima.periods(today)).containsExactly(ym("2026-09"), ym("2026-10"));
    assertThat(inUtc.periods(today)).containsExactly(ym("2026-09"), ym("2026-10"), ym("2026-11"));
  }

  @Test
  void aContractThatWasNeverFinishedHasNoTerminationMonth() {
    var open =
        ContractTerms.of(
            LocalDate.parse("2026-09-01"), null, null, ZoneId.of("America/Lima"), 5, 3);

    assertThat(open.terminatedIn()).isNull();
    assertThat(open.periods(TODAY)).containsExactly(ym("2026-09"), ym("2026-10"));
  }

  // --- the due date ---

  @ParameterizedTest
  @CsvSource({
    "31, 2026-02, 2026-02-28",
    "31, 2028-02, 2028-02-29",
    "30, 2026-02, 2026-02-28",
    "30, 2028-02, 2028-02-29",
    "29, 2026-02, 2026-02-28",
    "29, 2028-02, 2028-02-29",
    "31, 2026-04, 2026-04-30",
    "31, 2026-06, 2026-06-30",
    "31, 2026-01, 2026-01-31",
    "30, 2026-04, 2026-04-30",
    "1, 2026-10, 2026-10-01",
    "5, 2026-10, 2026-10-05"
  })
  void theDueDateIsThePaymentDayOrTheLastDayOfAShorterMonth(
      int day, String month, String expected) {
    assertThat(terms("2026-01-01", null, null, day, 0).dueDate(ym(month)))
        .isEqualTo(LocalDate.parse(expected));
  }

  // --- the state of a period ---

  @Test
  void aPeriodWithAnApprovedPaymentIsPaid() {
    var status =
        terms("2026-10-01", null, null, 5, 3)
            .status(
                ym("2026-10"), List.of(payment(7, "2026-10", PaymentStatus.APPROVED, null)), TODAY);

    assertThat(status.state()).isEqualTo(PeriodState.PAID);
    assertThat(status.paymentId()).isEqualTo(7L);
    assertThat(status.rejectionReason()).isNull();
    assertThat(status.dueDate()).isEqualTo(LocalDate.parse("2026-10-05"));
  }

  @Test
  void aPeriodWithAPendingVoucherIsUnderReviewEvenIfItIsLate() {
    var status =
        terms("2026-08-01", null, null, 5, 3)
            .status(
                ym("2026-08"), List.of(payment(7, "2026-08", PaymentStatus.PENDING, null)), TODAY);

    assertThat(status.state()).isEqualTo(PeriodState.UNDER_REVIEW);
    assertThat(status.paymentId()).isEqualTo(7L);
  }

  @Test
  void aRejectedVoucherLeavesThePeriodOpenAndAnewPendingOneIsUnderReview() {
    List<Payment> payments =
        List.of(
            payment(5, "2026-10", PaymentStatus.REJECTED, "Ilegible"),
            payment(9, "2026-10", PaymentStatus.PENDING, null));

    var status = terms("2026-10-01", null, null, 5, 3).status(ym("2026-10"), payments, TODAY);

    assertThat(status.state()).isEqualTo(PeriodState.UNDER_REVIEW);
    assertThat(status.paymentId()).isEqualTo(9L);
    assertThat(status.rejectionReason()).isNull();
  }

  @Test
  void aRejectedPeriodStaysUnpaidOrOverdueAndKeepsTheLastReason() {
    List<Payment> payments =
        List.of(
            payment(5, "2026-10", PaymentStatus.REJECTED, "Primero"),
            payment(9, "2026-10", PaymentStatus.REJECTED, "Segundo"));

    var early = terms("2026-10-01", null, null, 20, 3).status(ym("2026-10"), payments, TODAY);
    var late = terms("2026-10-01", null, null, 5, 3).status(ym("2026-10"), payments, TODAY);

    assertThat(early.state()).isEqualTo(PeriodState.UNPAID);
    assertThat(late.state()).isEqualTo(PeriodState.OVERDUE);
    assertThat(late.rejectionReason()).isEqualTo("Segundo");
    assertThat(late.paymentId()).isEqualTo(9L);
  }

  @Test
  void theLastRejectionIsTheNewestReviewEvenIfItsIdIsLower() {
    // ids come from blocks of 50 reserved by each instance: the later retry has the lower id
    Payment earlier = rejected(9, "2026-10", "Primero", "2026-10-02T10:00:00Z");
    Payment later = rejected(5, "2026-10", "Segundo", "2026-10-03T10:00:00Z");

    var status =
        terms("2026-10-01", null, null, 5, 3).status(ym("2026-10"), List.of(earlier, later), TODAY);

    assertThat(status.paymentId()).isEqualTo(5L);
    assertThat(status.rejectionReason()).isEqualTo("Segundo");
  }

  @Test
  void whenTwoRejectionsShareTheReviewInstantTheHigherIdBreaksTheTie() {
    Payment a = rejected(5, "2026-10", "A", "2026-10-02T10:00:00Z");
    Payment b = rejected(9, "2026-10", "B", "2026-10-02T10:00:00Z");

    var status = terms("2026-10-01", null, null, 5, 3).status(ym("2026-10"), List.of(b, a), TODAY);

    assertThat(status.paymentId()).isEqualTo(9L);
  }

  private static Payment rejected(long id, String period, String reason, String reviewedAt) {
    return new Payment(
        id,
        1L,
        10L,
        null,
        ym(period),
        BigDecimal.TEN,
        null,
        PaymentStatus.REJECTED,
        reason,
        Instant.parse(reviewedAt),
        9L);
  }

  @Test
  void aPeriodWithNothingIsUnpaidUntilTheDueDatePlusTheGraceDays() {
    // due 2026-10-05, grace 3: unpaid through the 8th, overdue from the 9th
    var contract = terms("2026-10-01", null, null, 5, 3);

    assertThat(contract.status(ym("2026-10"), List.of(), LocalDate.parse("2026-10-04")).state())
        .isEqualTo(PeriodState.UNPAID);
    assertThat(contract.status(ym("2026-10"), List.of(), LocalDate.parse("2026-10-05")).state())
        .isEqualTo(PeriodState.UNPAID);
    assertThat(contract.status(ym("2026-10"), List.of(), LocalDate.parse("2026-10-08")).state())
        .isEqualTo(PeriodState.UNPAID);
    assertThat(contract.status(ym("2026-10"), List.of(), LocalDate.parse("2026-10-09")).state())
        .isEqualTo(PeriodState.OVERDUE);
  }

  @Test
  void withNoGraceDaysAPeriodIsOverdueTheDayAfterTheDueDate() {
    var contract = terms("2026-10-01", null, null, 5, 0);

    assertThat(contract.status(ym("2026-10"), List.of(), LocalDate.parse("2026-10-05")).state())
        .isEqualTo(PeriodState.UNPAID);
    assertThat(contract.status(ym("2026-10"), List.of(), LocalDate.parse("2026-10-06")).state())
        .isEqualTo(PeriodState.OVERDUE);
  }

  @Test
  void withTheLongestGraceThePeriodIsOverdueOnlyAfter31MoreDays() {
    var contract = terms("2026-10-01", null, null, 5, 31);

    assertThat(contract.status(ym("2026-10"), List.of(), LocalDate.parse("2026-11-05")).state())
        .isEqualTo(PeriodState.UNPAID);
    assertThat(contract.status(ym("2026-10"), List.of(), LocalDate.parse("2026-11-06")).state())
        .isEqualTo(PeriodState.OVERDUE);
  }

  @Test
  void aPaymentDayOf31InFebruaryIsDueOnThe28th() {
    var contract = terms("2026-02-01", "2026-02-28", null, 31, 0);

    assertThat(contract.status(ym("2026-02"), List.of(), LocalDate.parse("2026-02-28")).state())
        .isEqualTo(PeriodState.UNPAID);
    assertThat(contract.status(ym("2026-02"), List.of(), LocalDate.parse("2026-03-01")).state())
        .isEqualTo(PeriodState.OVERDUE);
  }

  @Test
  void anIndefiniteContractListsEveryMonthWithItsState() {
    List<Payment> payments =
        List.of(
            payment(1, "2026-08", PaymentStatus.APPROVED, null),
            payment(2, "2026-09", PaymentStatus.PENDING, null));

    var statuses = terms("2026-07-01", null, null, 5, 3).statuses(payments, TODAY);

    assertThat(statuses)
        .extracting(PeriodStatus::period)
        .containsExactly(ym("2026-07"), ym("2026-08"), ym("2026-09"), ym("2026-10"));
    assertThat(statuses)
        .extracting(PeriodStatus::state)
        .containsExactly(
            PeriodState.OVERDUE, PeriodState.PAID, PeriodState.UNDER_REVIEW, PeriodState.OVERDUE);
  }

  @Test
  void thePaymentsOfAnotherPeriodDoNotCount() {
    var status =
        terms("2026-09-01", null, null, 5, 3)
            .status(
                ym("2026-10"), List.of(payment(1, "2026-09", PaymentStatus.APPROVED, null)), TODAY);

    assertThat(status.state()).isEqualTo(PeriodState.OVERDUE);
    assertThat(status.paymentId()).isNull();
  }
}
