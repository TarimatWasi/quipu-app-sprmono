package com.tarimatwasi.quipu.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * RN-38 (TAR-240): the credit of a contract is derived from its approved payments, period by
 * period, and what is left once its last period is reached is refunded.
 */
class CreditLedgerTest {

  private static final LocalDate TODAY = LocalDate.parse("2026-10-14");
  private static final BigDecimal MONTHLY = new BigDecimal("800");

  private static ContractTerms terms(String start, @Nullable String end) {
    return new ContractTerms(
        LocalDate.parse(start), end == null ? null : LocalDate.parse(end), null, 5, 3);
  }

  private static Payment approved(long id, String period, String verified) {
    return new Payment(
        id,
        1L,
        10L,
        null,
        YearMonth.parse(period),
        new BigDecimal(verified),
        new BigDecimal(verified),
        PaymentStatus.APPROVED,
        null,
        Instant.EPOCH,
        7L,
        Instant.EPOCH);
  }

  private static Payment pending(long id, String period) {
    return new Payment(
        id,
        1L,
        10L,
        null,
        YearMonth.parse(period),
        MONTHLY,
        null,
        PaymentStatus.PENDING,
        null,
        null,
        null,
        Instant.EPOCH);
  }

  @Test
  void withoutSurplusNothingIsDiscounted() {
    var ledger =
        CreditLedger.of(
            terms("2026-08-01", null), MONTHLY, List.of(approved(1, "2026-08", "800")), TODAY);

    assertThat(ledger.lines()).hasSize(3);
    assertThat(ledger.lines())
        .allSatisfy(line -> assertThat(line.creditApplied()).isEqualByComparingTo("0"));
    assertThat(ledger.lines().get(1).amountDue()).isEqualByComparingTo("800");
    assertThat(ledger.carry()).isEqualByComparingTo("0");
  }

  @Test
  void whatAPaymentVerifiedOverWhatItOwedIsDiscountedFromTheNextPeriod() {
    var ledger =
        CreditLedger.of(
            terms("2026-08-01", null),
            MONTHLY,
            List.of(approved(1, "2026-08", "850"), pending(2, "2026-09")),
            TODAY);

    var august = ledger.lines().get(0);
    assertThat(august.surplus()).isEqualByComparingTo("50");
    var september = ledger.lines().get(1);
    assertThat(september.creditApplied()).isEqualByComparingTo("50");
    assertThat(september.amountDue()).isEqualByComparingTo("750");
    assertThat(september.status().state()).isEqualTo(PeriodState.UNDER_REVIEW);
    // The credit was taken by September even though it is not approved yet
    assertThat(ledger.lines().get(2).creditApplied()).isEqualByComparingTo("0");
    assertThat(ledger.usable()).isEqualByComparingTo("0");
  }

  /** Decided 2026-11-01: a period the credit covers whole is paid without a payment. */
  @Test
  void aPeriodTheCreditCoversWholeIsPaidAndTheRestPassesOn() {
    var ledger =
        CreditLedger.of(
            terms("2026-08-01", null), MONTHLY, List.of(approved(1, "2026-08", "2000")), TODAY);

    var september = ledger.lines().get(1);
    assertThat(september.creditApplied()).isEqualByComparingTo("800");
    assertThat(september.amountDue()).isEqualByComparingTo("0");
    assertThat(september.status().state()).isEqualTo(PeriodState.PAID);
    assertThat(september.status().paymentId()).isNull();
    var october = ledger.lines().get(2);
    assertThat(october.creditApplied()).isEqualByComparingTo("400");
    assertThat(october.amountDue()).isEqualByComparingTo("400");
    assertThat(ledger.carry()).isEqualByComparingTo("0");
    assertThat(ledger.creditFor(YearMonth.of(2026, 11), MONTHLY)).isEqualByComparingTo("0");
  }

  @Test
  void theCreditLeftAfterTheLastPeriodIsToRefund() {
    var ledger =
        CreditLedger.of(
            terms("2026-08-01", "2026-09-30"),
            MONTHLY,
            List.of(approved(1, "2026-08", "800"), approved(2, "2026-09", "920")),
            TODAY);

    assertThat(ledger.over()).isTrue();
    assertThat(ledger.lines().get(1).last()).isTrue();
    assertThat(ledger.refundable()).isEqualByComparingTo("120");
    assertThat(ledger.usable()).isEqualByComparingTo("0");
    assertThat(ledger.carryPaymentId()).isEqualTo(2L);
  }

  @Test
  void anOpenContractKeepsItsCreditForTheNextMonth() {
    var ledger =
        CreditLedger.of(
            terms("2026-10-01", null), MONTHLY, List.of(approved(1, "2026-10", "900")), TODAY);

    assertThat(ledger.over()).isFalse();
    assertThat(ledger.usable()).isEqualByComparingTo("100");
    assertThat(ledger.refundable()).isEqualByComparingTo("0");
    assertThat(ledger.creditFor(YearMonth.of(2026, 11), MONTHLY)).isEqualByComparingTo("100");
  }

  @Test
  void theLastPeriodIsTheEarlierOfTheEndAndTheTermination() {
    var terminated =
        new ContractTerms(
            LocalDate.parse("2026-01-01"),
            LocalDate.parse("2026-12-31"),
            YearMonth.of(2026, 9),
            5,
            3);

    assertThat(terminated.lastPeriod()).isEqualTo(YearMonth.of(2026, 9));
    assertThat(terms("2026-01-01", "2026-06-15").lastPeriod()).isEqualTo(YearMonth.of(2026, 6));
    assertThat(terms("2026-01-01", null).lastPeriod()).isNull();
  }

  /** TAR-284: the credit kept by each payment, after the periods took it oldest first. */
  @Test
  void theUsableCreditSaysWhichPaymentsItWasLeftOverFromOldestFirst() {
    // September leaves 1200; October takes 800 of it and its own payment leaves 250 more.
    var ledger =
        CreditLedger.of(
            terms("2026-09-01", null),
            MONTHLY,
            List.of(approved(1, "2026-09", "2000"), approved(2, "2026-10", "250")),
            TODAY);

    assertThat(ledger.usable()).isEqualByComparingTo("650");
    assertThat(ledger.usableOrigins())
        .containsExactly(
            new GuestCredit.UsableOrigin(1L, YearMonth.of(2026, 9), new BigDecimal("400.00")),
            new GuestCredit.UsableOrigin(2L, YearMonth.of(2026, 10), new BigDecimal("250.00")));
  }

  /** TAR-284: a period takes the oldest credit first, so the newest one is what is left. */
  @Test
  void aPeriodTakesTheOldestCreditFirst() {
    // August leaves 1200 and September 500; September takes 800 of August's, October takes the
    // 400 August has left and 400 of September's.
    var ledger =
        CreditLedger.of(
            terms("2026-08-01", null),
            MONTHLY,
            List.of(approved(1, "2026-08", "2000"), approved(2, "2026-09", "500")),
            TODAY);

    assertThat(ledger.lines().get(2).creditApplied()).isEqualByComparingTo("800");
    assertThat(ledger.usable()).isEqualByComparingTo("100");
    assertThat(ledger.usableOrigins())
        .containsExactly(
            new GuestCredit.UsableOrigin(2L, YearMonth.of(2026, 9), new BigDecimal("100.00")));
  }

  /** TAR-284: a credit the next periods took whole leaves no origin. */
  @Test
  void aCreditTakenWholeLeavesNoOrigin() {
    var ledger =
        CreditLedger.of(
            terms("2026-08-01", null),
            MONTHLY,
            List.of(approved(1, "2026-08", "1000"), approved(2, "2026-09", "600")),
            TODAY);

    assertThat(ledger.lines().get(1).creditApplied()).isEqualByComparingTo("200");
    assertThat(ledger.usable()).isEqualByComparingTo("0");
    assertThat(ledger.usableOrigins()).isEmpty();
  }

  /** TAR-284: once the last period is reached the credit is to refund, not to use. */
  @Test
  void theCreditOfAContractThatEndedHasNoUsableOrigin() {
    var ledger =
        CreditLedger.of(
            terms("2026-08-01", "2026-09-30"),
            MONTHLY,
            List.of(approved(1, "2026-08", "900"), approved(2, "2026-09", "820")),
            TODAY);

    assertThat(ledger.refundable()).isEqualByComparingTo("120");
    assertThat(ledger.usableOrigins()).isEmpty();
  }

  @Test
  void aPeriodPaidByCreditIsNotDebt() {
    var debt =
        DebtCalculator.debt(
            terms("2026-08-01", null), MONTHLY, List.of(approved(1, "2026-08", "1600")), TODAY);

    assertThat(debt).extracting(DebtPeriod::period).containsExactly(YearMonth.of(2026, 10));
  }
}
