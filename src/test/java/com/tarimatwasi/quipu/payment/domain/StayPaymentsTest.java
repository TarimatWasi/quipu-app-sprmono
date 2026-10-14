package com.tarimatwasi.quipu.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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
}
