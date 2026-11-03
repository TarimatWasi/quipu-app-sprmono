package com.tarimatwasi.quipu.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.payment.domain.GuestCredit.Origin;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/** RN-38, RN-39 (TAR-240): the refunds are taken from what is to refund, oldest first. */
class GuestCreditTest {

  private static Origin ofContract(long paymentId, String amount, String startedOn) {
    return new Origin(
        paymentId,
        20L,
        null,
        YearMonth.parse(startedOn.substring(0, 7)),
        new BigDecimal(amount),
        LocalDate.parse(startedOn));
  }

  private static Origin ofStay(long paymentId, String amount, String startedOn) {
    return new Origin(
        paymentId, null, 30L, null, new BigDecimal(amount), LocalDate.parse(startedOn));
  }

  private static Refund refund(long id, String amount, String date) {
    return new Refund(
        id, 1L, 100L, new BigDecimal(amount), LocalDate.parse(date), "Yape", null, Instant.EPOCH);
  }

  @Test
  void withoutRefundsEverythingIsPendingAndTheUsableCreditCountsInTheBalance() {
    var credit =
        GuestCredit.of(new BigDecimal("30"), List.of(ofStay(2, "50", "2026-09-01")), List.of());

    assertThat(credit.pendingRefund()).isEqualByComparingTo("50");
    assertThat(credit.balance()).isEqualByComparingTo("80");
    assertThat(credit.toRefund()).isTrue();
    assertThat(credit.firstPending()).extracting(GuestCredit.Source::paymentId).isEqualTo(2L);
    assertThat(credit.lastRefund()).isNull();
  }

  @Test
  void aRefundIsTakenFromTheOldestSourceAndCanSpanTwo() {
    var credit =
        GuestCredit.of(
            BigDecimal.ZERO,
            List.of(ofStay(2, "50", "2026-09-01"), ofContract(1, "100", "2026-01-01")),
            List.of(refund(9, "120", "2026-10-03")));

    var contract = Objects.requireNonNull(credit.sourcesByPayment().get(1L));
    var stay = Objects.requireNonNull(credit.sourcesByPayment().get(2L));
    assertThat(contract.refundedWhole()).isTrue();
    assertThat(contract.lastRefund()).extracting(Refund::id).isEqualTo(9L);
    assertThat(stay.refunded()).isEqualByComparingTo("20");
    assertThat(stay.pending()).isEqualByComparingTo("30");
    assertThat(credit.pendingRefund()).isEqualByComparingTo("30");
    assertThat(credit.firstPending()).extracting(GuestCredit.Source::paymentId).isEqualTo(2L);
  }

  @Test
  void onceEverythingIsRefundedNothingIsPending() {
    var credit =
        GuestCredit.of(
            BigDecimal.ZERO,
            List.of(ofStay(2, "50", "2026-09-01")),
            List.of(refund(9, "20", "2026-10-03"), refund(8, "30", "2026-10-05")));

    assertThat(credit.toRefund()).isFalse();
    assertThat(credit.firstPending()).isNull();
    assertThat(Objects.requireNonNull(credit.sourcesByPayment().get(2L)).lastRefund())
        .extracting(Refund::id)
        .isEqualTo(8L);
    assertThat(credit.lastRefund()).extracting(Refund::id).isEqualTo(8L);
  }
}
