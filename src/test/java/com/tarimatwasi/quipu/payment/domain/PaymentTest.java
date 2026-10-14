package com.tarimatwasi.quipu.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PaymentTest {

  private static final Instant AT = Instant.parse("2026-10-14T15:00:00Z");

  private static Payment pending() {
    return new Payment(
        1L,
        2L,
        3L,
        null,
        YearMonth.of(2026, 10),
        new BigDecimal("800.00"),
        "Yape",
        PaymentStatus.PENDING,
        null,
        null,
        null);
  }

  @Test
  void aNewVoucherIsPendingAndAlive() {
    assertThat(pending().pending()).isTrue();
    assertThat(pending().alive()).isTrue();
  }

  @Test
  void approving_resolvesItWithWhoAndWhen() {
    Payment approved = pending().approve(9L, AT);

    assertThat(approved.status()).isEqualTo(PaymentStatus.APPROVED);
    assertThat(approved.reviewedById()).isEqualTo(9L);
    assertThat(approved.reviewedAt()).isEqualTo(AT);
    assertThat(approved.rejectionReason()).isNull();
    assertThat(approved.pending()).isFalse();
    assertThat(approved.alive()).isTrue();
    assertThat(approved.id()).isEqualTo(1L);
    assertThat(approved.amount()).isEqualByComparingTo("800.00");
    assertThat(approved.period()).isEqualTo(YearMonth.of(2026, 10));
  }

  @Test
  void rejecting_keepsTheReasonWithoutTheSpacesAtItsEdges() {
    Payment rejected = pending().reject(9L, "  Voucher ilegible \n", AT);

    assertThat(rejected.status()).isEqualTo(PaymentStatus.REJECTED);
    assertThat(rejected.rejectionReason()).isEqualTo("Voucher ilegible");
    assertThat(rejected.reviewedById()).isEqualTo(9L);
    assertThat(rejected.alive()).isFalse();
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "   ", "\t", "\n", "\u00A0", "\u2003"})
  void rejecting_withoutAReasonIsRefused(String reason) {
    assertThatThrownBy(() -> pending().reject(9L, reason, AT))
        .isInstanceOf(InvalidRejectionReasonException.class);
  }

  @Test
  void theReasonHasAtMost500CharactersCountedAfterTrimming() {
    assertThat(pending().reject(9L, "x".repeat(500), AT).rejectionReason()).hasSize(500);
    assertThat(pending().reject(9L, "  " + "x".repeat(500) + "  ", AT).rejectionReason())
        .hasSize(500);
    assertThatThrownBy(() -> pending().reject(9L, "x".repeat(501), AT))
        .isInstanceOf(InvalidRejectionReasonException.class);
  }

  @Test
  void aResolvedPaymentIsNotResolvedAgain() {
    Payment approved = pending().approve(9L, AT);
    Payment rejected = pending().reject(9L, "No coincide", AT);

    assertThatThrownBy(() -> approved.approve(9L, AT))
        .isInstanceOf(PaymentNotPendingException.class);
    assertThatThrownBy(() -> approved.reject(9L, "Tarde", AT))
        .isInstanceOf(PaymentNotPendingException.class);
    assertThatThrownBy(() -> rejected.approve(9L, AT))
        .isInstanceOf(PaymentNotPendingException.class);
    assertThatThrownBy(() -> rejected.reject(9L, "Otra vez", AT))
        .isInstanceOf(PaymentNotPendingException.class);
  }

  @Test
  void aResolvedPaymentIsReportedBeforeABadReason() {
    assertThatThrownBy(() -> pending().approve(9L, AT).reject(9L, " ", AT))
        .isInstanceOf(PaymentNotPendingException.class);
  }

  @Test
  void aPaymentOfAStayHasNoPeriod() {
    Payment stay =
        new Payment(
            1L, 2L, null, 7L, null, BigDecimal.TEN, null, PaymentStatus.PENDING, null, null, null);

    assertThat(stay.approve(9L, AT).stayId()).isEqualTo(7L);
    assertThat(stay.approve(9L, AT).period()).isNull();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @ValueSource(strings = {"0.01", "1", "800.5", "800.50", "800.500", "99999999.99"})
  void validAmount_acceptsAPositiveAmountOfTwoDecimalsThatFitsTheColumn(String amount) {
    assertThat(Payment.validAmount(new BigDecimal(amount))).isTrue();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @ValueSource(strings = {"0", "0.00", "-1", "0.001", "800.123", "100000000", "99999999.991"})
  void validAmount_refusesTheRest(String amount) {
    assertThat(Payment.validAmount(new BigDecimal(amount))).isFalse();
  }
}
