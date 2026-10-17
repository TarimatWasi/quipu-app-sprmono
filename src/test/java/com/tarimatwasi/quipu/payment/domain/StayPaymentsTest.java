package com.tarimatwasi.quipu.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** RN-03, CU-21: the single payment of a stay is due by the exit date. */
class StayPaymentsTest {

  private static final LocalDate TODAY = LocalDate.parse("2026-10-14");

  private static Payment payment(long id, PaymentStatus status) {
    return new Payment(
        id,
        1L,
        null,
        5L,
        null,
        BigDecimal.TEN,
        null,
        status,
        status == PaymentStatus.REJECTED ? "No coincide" : null,
        null,
        null);
  }

  private static PeriodState state(String endDate, Payment... payments) {
    return StayPayments.state(LocalDate.parse(endDate), List.of(payments), TODAY);
  }

  @Test
  void aStayInTheFutureOrInCourseIsUnpaidWithoutAPayment() {
    assertThat(state("2026-10-20")).isEqualTo(PeriodState.UNPAID);
    assertThat(state("2026-11-30")).isEqualTo(PeriodState.UNPAID);
  }

  @Test
  void aStayThatEndsTodayIsStillUnpaidAndOverdueFromTheNextDay() {
    assertThat(state("2026-10-14")).isEqualTo(PeriodState.UNPAID);
    assertThat(state("2026-10-13")).isEqualTo(PeriodState.OVERDUE);
  }

  @Test
  void aPastStayWithoutAnApprovedPaymentIsOverdue() {
    assertThat(state("2026-09-01")).isEqualTo(PeriodState.OVERDUE);
    assertThat(state("2026-09-01", payment(1, PaymentStatus.REJECTED)))
        .isEqualTo(PeriodState.OVERDUE);
  }

  @Test
  void anApprovedPaymentMakesItPaidWhateverTheDate() {
    assertThat(state("2026-09-01", payment(1, PaymentStatus.APPROVED))).isEqualTo(PeriodState.PAID);
    assertThat(state("2026-11-01", payment(1, PaymentStatus.APPROVED))).isEqualTo(PeriodState.PAID);
  }

  @Test
  void aPendingVoucherIsUnderReviewEvenAfterTheExit() {
    assertThat(state("2026-11-01", payment(1, PaymentStatus.PENDING)))
        .isEqualTo(PeriodState.UNDER_REVIEW);
    assertThat(state("2026-09-01", payment(1, PaymentStatus.PENDING)))
        .isEqualTo(PeriodState.UNDER_REVIEW);
  }

  @Test
  void aRejectedVoucherFollowedByANewPendingOneIsUnderReview() {
    assertThat(
            state(
                "2026-11-01",
                payment(1, PaymentStatus.REJECTED),
                payment(2, PaymentStatus.PENDING)))
        .isEqualTo(PeriodState.UNDER_REVIEW);
  }

  @Test
  void aRejectedVoucherLeavesAFutureStayUnpaid() {
    assertThat(state("2026-11-01", payment(1, PaymentStatus.REJECTED)))
        .isEqualTo(PeriodState.UNPAID);
  }

  // --- the payment the status points to ---

  private static Payment rejected(long id, String reason, String reviewedAt) {
    return new Payment(
        id,
        1L,
        null,
        5L,
        null,
        BigDecimal.TEN,
        null,
        PaymentStatus.REJECTED,
        reason,
        Instant.parse(reviewedAt),
        9L);
  }

  @Test
  void aRejectedVoucherKeepsItsReasonButTheStayHasNoRejectedStateOfItsOwn() {
    StayPayments.StayStatus status =
        StayPayments.status(
            LocalDate.parse("2026-10-20"),
            List.of(
                rejected(7, "Monto incompleto", "2026-10-02T10:00:00Z"),
                rejected(3, "Ilegible", "2026-10-05T10:00:00Z")),
            TODAY);

    assertThat(status.state()).isEqualTo(PeriodState.UNPAID);
    assertThat(status.paymentId()).isEqualTo(3L);
    assertThat(status.rejectionReason()).isEqualTo("Ilegible");
  }

  @Test
  void aLivePaymentHidesTheReasonOfAnEarlierRejection() {
    StayPayments.StayStatus status =
        StayPayments.status(
            LocalDate.parse("2026-10-20"),
            List.of(
                rejected(3, "Ilegible", "2026-10-05T10:00:00Z"), payment(4, PaymentStatus.PENDING)),
            TODAY);

    assertThat(status.state()).isEqualTo(PeriodState.UNDER_REVIEW);
    assertThat(status.paymentId()).isEqualTo(4L);
    assertThat(status.rejectionReason()).isNull();
  }

  // --- the stay a voucher of the guest pays (CU-22) ---

  private static StayTerms stay(long id, String start, String end) {
    return new StayTerms(id, LocalDate.parse(start), LocalDate.parse(end), BigDecimal.TEN);
  }

  @Test
  void theVoucherPaysTheStayInCourseEvenIfItAlreadyHasAPayment() {
    StayTerms inCourse = stay(1, "2026-10-10", "2026-10-20");
    List<StayTerms> stays = List.of(stay(2, "2026-11-01", "2026-11-05"), inCourse);

    assertThat(StayPayments.voucherStay(stays, id -> true, TODAY)).contains(inCourse);
  }

  @Test
  void withoutAStayInCourseItPaysTheEarliestToComeThatIsNotPaidOrUnderReview() {
    StayTerms first = stay(1, "2026-11-01", "2026-11-05");
    StayTerms second = stay(2, "2026-12-01", "2026-12-05");
    List<StayTerms> stays = List.of(second, stay(3, "2026-09-01", "2026-09-05"), first);

    assertThat(StayPayments.voucherStay(stays, id -> false, TODAY)).contains(first);
    assertThat(StayPayments.voucherStay(stays, Set.of(1L)::contains, TODAY)).contains(second);
    // every stay to come is taken: the earliest one, so the guest hears why
    assertThat(StayPayments.voucherStay(stays, id -> true, TODAY)).contains(first);
  }

  @Test
  void aGuestWithOnlyPastStaysOrNoneHasNoStayToPay() {
    assertThat(
            StayPayments.voucherStay(
                List.of(stay(3, "2026-09-01", "2026-10-13")), id -> false, TODAY))
        .isEmpty();
    assertThat(StayPayments.voucherStay(List.of(), id -> false, TODAY)).isEmpty();
  }
}
