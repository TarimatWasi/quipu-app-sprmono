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
  void theIncomeIsPaymentsPlusFinesLessRefundsAndTheNetIsIncomeMinusExpenses() {
    var balance =
        MonthlyBalance.of(
            OCTOBER, total("1100.30", 3), total("25.30", 1), total("50", 2), total("2000", 4));

    assertThat(balance.month()).isEqualTo(OCTOBER);
    assertThat(balance.incomeFromPayments()).hasToString("1100.30");
    assertThat(balance.paymentsCount()).isEqualTo(3);
    assertThat(balance.incomeFromFines()).hasToString("25.30");
    assertThat(balance.finesCount()).isEqualTo(1);
    assertThat(balance.refundsTotal()).hasToString("50.00");
    assertThat(balance.refundsCount()).isEqualTo(2);
    assertThat(balance.totalIncome()).hasToString("1075.60");
    assertThat(balance.totalExpenses()).hasToString("2000.00");
    assertThat(balance.expensesCount()).isEqualTo(4);
    assertThat(balance.net()).hasToString("-924.40");
  }

  private static MonthTotal total(String amount, long count) {
    return new MonthTotal(new BigDecimal(amount), count);
  }

  @Test
  void theCentsAddUpExactly() {
    var balance =
        MonthlyBalance.of(
            OCTOBER, total("0.10", 1), total("0.20", 1), MonthTotal.NONE, total("0.3", 1));

    assertThat(balance.totalIncome()).hasToString("0.30");
    assertThat(balance.net()).hasToString("0.00");
  }

  @Test
  void aMonthWithoutMovementsIsZerosWithTwoDecimals() {
    var balance =
        MonthlyBalance.of(
            OCTOBER, MonthTotal.NONE, MonthTotal.NONE, MonthTotal.NONE, MonthTotal.NONE);

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
                    OCTOBER, total("0.005", 1), MonthTotal.NONE, MonthTotal.NONE, MonthTotal.NONE))
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
                    YearMonth.of(0, 1),
                    MonthTotal.NONE,
                    MonthTotal.NONE,
                    MonthTotal.NONE,
                    MonthTotal.NONE))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
