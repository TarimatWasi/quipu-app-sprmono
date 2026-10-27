package com.tarimatwasi.quipu.expense.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;
import org.junit.jupiter.api.Test;

/** TAR-221: the months of an expense go from year 1 to year 9999, as those of a fine. */
class ExpenseTest {

  @Test
  void acceptsTheYearsFromOneTo9999() {
    assertThat(Expense.isValidMonth(YearMonth.of(1, 1))).isTrue();
    assertThat(Expense.isValidMonth(YearMonth.of(2026, 9))).isTrue();
    assertThat(Expense.isValidMonth(YearMonth.of(9999, 12))).isTrue();
  }

  @Test
  void refusesYearZeroTheYearsBeforeAndFiveDigits() {
    assertThat(Expense.isValidMonth(YearMonth.of(0, 12))).isFalse();
    assertThat(Expense.isValidMonth(YearMonth.of(-1, 1))).isFalse();
    assertThat(Expense.isValidMonth(YearMonth.of(10_000, 1))).isFalse();
  }
}
