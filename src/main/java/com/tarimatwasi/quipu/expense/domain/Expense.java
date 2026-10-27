package com.tarimatwasi.quipu.expense.domain;

import java.math.BigDecimal;
import java.time.YearMonth;
import org.jspecify.annotations.Nullable;

/** An operating expense of one month; the amount is in soles, greater than 0 (RF-07). */
public record Expense(
    Long id,
    ExpenseCategory category,
    BigDecimal amount,
    YearMonth month,
    @Nullable String description) {

  /**
   * Year 0 of Java is 1 BC for PostgreSQL, and it reads back as year 1: the months start in year 1.
   * The contract allows four digits at most. The same range as the fines ({@code Fine}): the domain
   * depends on no other module, so the rule is repeated here (TAR-221).
   */
  private static final int MIN_YEAR = 1;

  private static final int MAX_YEAR = 9999;

  /** A month that the base stores and reads back as it is: from year 1 to year 9999. */
  public static boolean isValidMonth(YearMonth month) {
    return month.getYear() >= MIN_YEAR && month.getYear() <= MAX_YEAR;
  }
}
