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
        null,
        PaymentStatus.PENDING,
        null,
        null,
        null,
        AT.minusSeconds(3600));
  }

  @Test
  void aNewPaymentIsPendingAndResolvable() {
    assertThat(pending().pending()).isTrue();
    assertThat(pending().resolvable()).isTrue();
  }

  @Test
  void approving_resolvesItWithWhoAndWhen() {
    Payment approved = pending().approve(9L, new BigDecimal("800.00"), AT);

    assertThat(approved.status()).isEqualTo(PaymentStatus.APPROVED);
    assertThat(approved.reviewedById()).isEqualTo(9L);
    assertThat(approved.reviewedAt()).isEqualTo(AT);
    assertThat(approved.rejectionReason()).isNull();
    assertThat(approved.pending()).isFalse();
    assertThat(approved.resolvable()).isFalse();
    assertThat(approved.verifiedAmount()).isEqualByComparingTo("800.00");
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
    // TAR-239: a rejected payment is corrected, so the administrator can still decide on it.
    assertThat(rejected.resolvable()).isTrue();
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
  void anApprovedPaymentIsNotResolvedAgainButARejectedOneIs() {
    Payment approved = pending().approve(9L, new BigDecimal("800.00"), AT);
    Payment rejected = pending().reject(9L, "No coincide", AT);

    assertThatThrownBy(() -> approved.approve(9L, new BigDecimal("800.00"), AT))
        .isInstanceOf(PaymentNotPendingException.class);
    assertThatThrownBy(() -> approved.reject(9L, "Tarde", AT))
        .isInstanceOf(PaymentNotPendingException.class);
    assertThat(rejected.approve(9L, new BigDecimal("800.00"), AT).status())
        .isEqualTo(PaymentStatus.APPROVED);
    assertThat(rejected.reject(9L, "Otra vez", AT).rejectionReason()).isEqualTo("Otra vez");
  }

  @Test
  void aSupersededRejectionIsHistoryAndNotResolvedAgain() {
    Payment old =
        new Payment(
            1L,
            2L,
            3L,
            null,
            YearMonth.of(2026, 10),
            BigDecimal.TEN,
            null,
            PaymentStatus.SUPERSEDED,
            "No coincide",
            AT,
            9L,
            AT);

    assertThat(old.resolvable()).isFalse();
    assertThat(old.status().shown()).isEqualTo(PaymentStatus.REJECTED);
    assertThatThrownBy(() -> old.reject(9L, "Otra", AT))
        .isInstanceOf(PaymentNotPendingException.class);
  }

  @Test
  void theGuestCorrectsOnlyARejectedPaymentAndSendsItAgain() {
    Payment rejected = pending().reject(9L, "No coincide", AT);
    Instant later = AT.plusSeconds(60);

    Payment sent = rejected.corrected(new BigDecimal("850.00"), true, later);

    assertThat(sent.status()).isEqualTo(PaymentStatus.PENDING);
    assertThat(sent.amount()).isEqualByComparingTo("850.00");
    assertThat(sent.submittedAt()).isEqualTo(later);
    assertThat(sent.rejectionReason()).isNull();
    assertThat(sent.reviewedAt()).isNull();
    assertThat(sent.reviewedById()).isNull();
    assertThatThrownBy(() -> pending().corrected(BigDecimal.TEN, true, later))
        .isInstanceOf(CorrectionNotAllowedException.class);
  }

  @Test
  void theAdministratorCorrectsAPendingOrRejectedPaymentAndItsStatusStays() {
    Payment rejected = pending().reject(9L, "No coincide", AT);

    Payment corrected = rejected.corrected(new BigDecimal("850.00"), false, AT.plusSeconds(60));

    assertThat(corrected.status()).isEqualTo(PaymentStatus.REJECTED);
    assertThat(corrected.submittedAt()).isEqualTo(rejected.submittedAt());
    assertThat(pending().corrected(BigDecimal.TEN, false, AT).status())
        .isEqualTo(PaymentStatus.PENDING);
    Payment approved = pending().approve(9L, new BigDecimal("800.00"), AT);
    assertThatThrownBy(() -> approved.corrected(BigDecimal.TEN, false, AT))
        .isInstanceOf(CorrectionNotAllowedException.class);
  }

  @Test
  void aResolvedPaymentIsReportedBeforeABadReason() {
    assertThatThrownBy(
            () -> pending().approve(9L, new BigDecimal("800.00"), AT).reject(9L, " ", AT))
        .isInstanceOf(PaymentNotPendingException.class);
  }

  @Test
  void aPaymentOfAStayHasNoPeriod() {
    Payment stay =
        new Payment(
            1L,
            2L,
            null,
            7L,
            null,
            BigDecimal.TEN,
            null,
            PaymentStatus.PENDING,
            null,
            null,
            null,
            AT);

    assertThat(stay.approve(9L, new BigDecimal("800.00"), AT).stayId()).isEqualTo(7L);
    assertThat(stay.approve(9L, new BigDecimal("800.00"), AT).period()).isNull();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @ValueSource(strings = {"0.01", "1", "800.5", "800.50", "800.500", "99999999.99"})
  void validAmount_acceptsAPositiveAmountOfTwoDecimalsThatFitsTheColumn(String amount) {
    assertThat(Payment.validAmount(new BigDecimal(amount))).isTrue();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @ValueSource(
      strings = {
        "0",
        "0.00",
        "-1",
        "0.001",
        "800.123",
        "100000000",
        "99999999.991",
        "8E+2",
        "1e2",
        "0.8E+3"
      })
  void validAmount_refusesTheRest(String amount) {
    assertThat(Payment.validAmount(new BigDecimal(amount))).isFalse();
  }

  @Test
  void theReasonLimitCountsCharactersAndNotUtf16Units() {
    String emoji = new String(Character.toChars(0x1F600));

    assertThat(pending().reject(9L, emoji.repeat(500), AT).rejectionReason())
        .isEqualTo(emoji.repeat(500));
    assertThatThrownBy(() -> pending().reject(9L, emoji.repeat(501), AT))
        .isInstanceOf(InvalidRejectionReasonException.class);
  }
}
