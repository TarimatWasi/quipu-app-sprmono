package com.tarimatwasi.quipu.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** RN-25, RN-35: the debt counts the periods past their due date, without the grace days. */
class DebtCalculatorTest {

  private static final LocalDate TODAY = LocalDate.parse("2026-10-14");

  private static ContractTerms terms(String start, @Nullable String end, int day, int grace) {
    return new ContractTerms(
        LocalDate.parse(start), end == null ? null : LocalDate.parse(end), null, day, grace);
  }

  private static Payment payment(long id, String period, PaymentStatus status) {
    return new Payment(
        id,
        1L,
        10L,
        null,
        YearMonth.parse(period),
        BigDecimal.TEN,
        null,
        status,
        status == PaymentStatus.REJECTED ? "No coincide" : null,
        null,
        null);
  }

  private static List<String> debt(ContractTerms terms, List<Payment> payments) {
    return DebtCalculator.debt(terms, payments, TODAY).stream()
        .map(d -> d.period().toString())
        .toList();
  }

  @Test
  void everyUnpaidPeriodPastItsDueDateIsDebtOldestFirst() {
    assertThat(debt(terms("2026-07-01", null, 5, 3), List.of()))
        .containsExactly("2026-07", "2026-08", "2026-09", "2026-10");
  }

  @Test
  void anApprovedPaymentClearsItsPeriod() {
    assertThat(
            debt(
                terms("2026-07-01", null, 5, 3),
                List.of(payment(1, "2026-08", PaymentStatus.APPROVED))))
        .containsExactly("2026-07", "2026-09", "2026-10");
  }

  @Test
  void aVoucherUnderReviewOrRejectedDoesNotClearTheDebt() {
    assertThat(
            debt(
                terms("2026-09-01", null, 5, 3),
                List.of(
                    payment(1, "2026-09", PaymentStatus.PENDING),
                    payment(2, "2026-10", PaymentStatus.REJECTED))))
        .containsExactly("2026-09", "2026-10");
  }

  @Test
  void aPeriodDueTodayIsNotYetDebt() {
    assertThat(debt(terms("2026-10-01", null, 14, 0), List.of())).isEmpty();
    assertThat(debt(terms("2026-10-01", null, 13, 0), List.of())).containsExactly("2026-10");
  }

  @Test
  void theGraceDaysDoNotDelayTheDebt() {
    // due the 5th with 31 grace days is not OVERDUE yet, but it is already debt
    var contract = terms("2026-10-01", null, 5, 31);

    assertThat(debt(contract, List.of())).containsExactly("2026-10");
    assertThat(contract.status(YearMonth.parse("2026-10"), List.of(), TODAY).state())
        .isEqualTo(PeriodState.UNPAID);
  }

  @Test
  void aPaymentDayLaterThanTodayInTheCurrentMonthIsNotDebt() {
    assertThat(debt(terms("2026-09-01", null, 31, 0), List.of())).containsExactly("2026-09");
  }

  @Test
  void theDueDateOfTheDebtIsTheOneOfTheShorterMonth() {
    var found = DebtCalculator.debt(terms("2026-02-01", "2026-02-28", 31, 0), List.of(), TODAY);

    assertThat(found)
        .containsExactly(new DebtPeriod(YearMonth.parse("2026-02"), LocalDate.parse("2026-02-28")));
  }

  @Test
  void aContractThatEndedOnlyOwesItsOwnMonths() {
    assertThat(debt(terms("2026-05-01", "2026-07-15", 5, 3), List.of()))
        .containsExactly("2026-05", "2026-06", "2026-07");
  }

  @Test
  void aContractFinishedByHandOwesNothingAfterItsLastMonth() {
    var finished =
        new ContractTerms(LocalDate.parse("2026-05-01"), null, YearMonth.parse("2026-06"), 5, 3);

    assertThat(DebtCalculator.debt(finished, List.of(), TODAY))
        .extracting(DebtPeriod::period)
        .containsExactly(YearMonth.parse("2026-05"), YearMonth.parse("2026-06"));
  }

  @Test
  void aContractThatHasNotStartedOwesNothing() {
    assertThat(debt(terms("2026-11-01", null, 5, 3), List.of())).isEmpty();
  }

  @Test
  void aContractThatStartsOnTheLastDayOfTheMonthOwesThatMonthOnceItIsDue() {
    assertThat(DebtCalculator.debt(terms("2026-09-30", null, 5, 0), List.of(), TODAY))
        .extracting(DebtPeriod::period)
        .containsExactly(YearMonth.parse("2026-09"), YearMonth.parse("2026-10"));
  }
}
