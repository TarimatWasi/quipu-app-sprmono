package com.tarimatwasi.quipu.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tarimatwasi.quipu.payment.domain.GuestCredit.Origin;
import com.tarimatwasi.quipu.payment.domain.GuestCredit.UsableOrigin;
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

  private static UsableOrigin usable(long paymentId, String period, String amount) {
    return new UsableOrigin(paymentId, YearMonth.parse(period), new BigDecimal(amount));
  }

  private static Refund refund(long id, String amount, String date) {
    return new Refund(
        id, 1L, 100L, new BigDecimal(amount), LocalDate.parse(date), "Yape", null, Instant.EPOCH);
  }

  @Test
  void withoutRefundsEverythingIsPendingAndTheUsableCreditCountsInTheBalance() {
    var credit =
        GuestCredit.of(
            List.of(usable(5, "2026-10", "30")), List.of(ofStay(2, "50", "2026-09-01")), List.of());

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
            List.of(),
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
            List.of(),
            List.of(ofStay(2, "50", "2026-09-01")),
            List.of(refund(9, "20", "2026-10-03"), refund(8, "30", "2026-10-05")));

    assertThat(credit.toRefund()).isFalse();
    assertThat(credit.firstPending()).isNull();
    assertThat(Objects.requireNonNull(credit.sourcesByPayment().get(2L)).lastRefund())
        .extracting(Refund::id)
        .isEqualTo(8L);
    assertThat(credit.lastRefund()).extracting(Refund::id).isEqualTo(8L);
  }

  /** TAR-284: the usable credit is what its origins add, oldest first, without empty ones. */
  @Test
  void theUsableCreditIsTheSumOfItsOriginsOldestFirst() {
    var credit =
        GuestCredit.of(
            List.of(
                usable(7, "2026-10", "250.00"),
                usable(6, "2026-09", "0.00"),
                usable(4, "2026-09", "400.00")),
            List.of(ofContract(1, "100", "2026-01-01")),
            List.of());

    assertThat(credit.usable()).isEqualByComparingTo("650");
    assertThat(credit.usableOrigins())
        .containsExactly(usable(4, "2026-09", "400.00"), usable(7, "2026-10", "250.00"));
    // What is to refund stays apart from what is still used
    assertThat(credit.usableOrigins()).extracting(UsableOrigin::paymentId).doesNotContain(1L);
    assertThat(credit.balance()).isEqualByComparingTo("750");
  }

  @Test
  void withoutUsableCreditThereIsNoOrigin() {
    var credit = GuestCredit.of(List.of(), List.of(), List.of());

    assertThat(credit.usable()).isEqualByComparingTo("0");
    assertThat(credit.usableOrigins()).isEmpty();
  }

  @Test
  void aUsableCreditThatIsNotTheSumOfItsOriginsIsRejected() {
    List<UsableOrigin> origins = List.of(usable(4, "2026-09", "400.00"));
    List<GuestCredit.Source> none = List.of();
    List<Refund> noRefunds = List.of();
    var usable = new BigDecimal("500.00");

    assertThatThrownBy(() -> new GuestCredit(usable, origins, none, noRefunds))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
