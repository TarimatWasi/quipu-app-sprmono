package com.tarimatwasi.quipu.payment.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;

/**
 * RF-18: the income and the expenses of one month, in soles with two decimals. The income is the
 * approved payments attributed to the month plus its fines; the net is the income minus the
 * expenses, negative when the expenses are higher.
 */
public record MonthlyBalance(
    YearMonth month,
    BigDecimal incomeFromPayments,
    BigDecimal incomeFromFines,
    BigDecimal totalIncome,
    BigDecimal totalExpenses,
    BigDecimal net) {

  /**
   * Year 0 of Java is 1 BC for PostgreSQL, and it reads back as year 1: the months start in year 1.
   * The contract allows four digits at most. The same range as the fines and the expenses: the
   * domain depends on no other module, so the rule is repeated here (TAR-48).
   */
  private static final int MIN_YEAR = 1;

  private static final int MAX_YEAR = 9999;

  private static final int SCALE = 2;

  public MonthlyBalance {
    if (!isValidMonth(month)) {
      throw new IllegalArgumentException("month out of range");
    }
  }

  /** A month that the base stores and reads back as it is: from year 1 to year 9999. */
  public static boolean isValidMonth(YearMonth month) {
    return month.getYear() >= MIN_YEAR && month.getYear() <= MAX_YEAR;
  }

  /**
   * The balance of the three sums of the month, each with at most two decimals as the base keeps
   * them. Added up as decimals, never as floating point.
   *
   * @throws ArithmeticException if an amount has more than two decimals: it is not rounded
   */
  public static MonthlyBalance of(
      YearMonth month, BigDecimal payments, BigDecimal fines, BigDecimal expenses) {
    BigDecimal fromPayments = cents(payments);
    BigDecimal fromFines = cents(fines);
    BigDecimal outgoing = cents(expenses);
    BigDecimal income = fromPayments.add(fromFines);
    return new MonthlyBalance(
        month, fromPayments, fromFines, income, outgoing, income.subtract(outgoing));
  }

  private static BigDecimal cents(BigDecimal amount) {
    return amount.setScale(SCALE, RoundingMode.UNNECESSARY);
  }
}
