package com.tarimatwasi.quipu.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

/** RF-18: the income, the expenses and the net of a month, in soles with two decimals. */
class MonthlyBalanceTest {

  private static final YearMonth OCTOBER = YearMonth.of(2026, 10);

  @Test
  void theIncomeIsPaymentsPlusFinesAndTheNetIsIncomeMinusExpenses() {
    var balance =
        MonthlyBalance.of(
            OCTOBER, new BigDecimal("1100.30"), new BigDecimal("25.30"), new BigDecimal("2000"));

    assertThat(balance.month()).isEqualTo(OCTOBER);
    assertThat(balance.incomeFromPayments()).hasToString("1100.30");
    assertThat(balance.incomeFromFines()).hasToString("25.30");
    assertThat(balance.totalIncome()).hasToString("1125.60");
    assertThat(balance.totalExpenses()).hasToString("2000.00");
    assertThat(balance.net()).hasToString("-874.40");
  }

  @Test
  void theCentsAddUpExactly() {
    var balance =
        MonthlyBalance.of(
            OCTOBER, new BigDecimal("0.10"), new BigDecimal("0.20"), new BigDecimal("0.3"));

    assertThat(balance.totalIncome()).hasToString("0.30");
    assertThat(balance.net()).hasToString("0.00");
  }

  @Test
  void aMonthWithoutMovementsIsZerosWithTwoDecimals() {
    var balance = MonthlyBalance.of(OCTOBER, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

    assertThat(balance.incomeFromPayments()).hasToString("0.00");
    assertThat(balance.incomeFromFines()).hasToString("0.00");
    assertThat(balance.totalIncome()).hasToString("0.00");
    assertThat(balance.totalExpenses()).hasToString("0.00");
    assertThat(balance.net()).hasToString("0.00");
  }

  @Test
  void anAmountWithMoreThanTwoDecimalsIsRefusedInsteadOfRounded() {
    assertThatThrownBy(
            () ->
                MonthlyBalance.of(
                    OCTOBER, new BigDecimal("0.005"), BigDecimal.ZERO, BigDecimal.ZERO))
        .isInstanceOf(ArithmeticException.class);
  }

  @Test
  void theMonthsGoFromYearOneToYear9999() {
    assertThat(MonthlyBalance.isValidMonth(YearMonth.of(1, 1))).isTrue();
    assertThat(MonthlyBalance.isValidMonth(YearMonth.of(9999, 12))).isTrue();
    assertThat(MonthlyBalance.isValidMonth(YearMonth.of(0, 12))).isFalse();
    assertThat(MonthlyBalance.isValidMonth(YearMonth.of(-1, 1))).isFalse();
    assertThat(MonthlyBalance.isValidMonth(YearMonth.of(10000, 1))).isFalse();
  }

  @Test
  void aBalanceOfAMonthOutsideTheRangeCannotBeBuilt() {
    assertThatThrownBy(
            () ->
                MonthlyBalance.of(
                    YearMonth.of(0, 1), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
