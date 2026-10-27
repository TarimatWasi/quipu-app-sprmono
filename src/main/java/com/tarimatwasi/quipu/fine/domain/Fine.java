package com.tarimatwasi.quipu.fine.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import org.jspecify.annotations.Nullable;

/**
 * A fine charged to a guest, active or inactive; it is direct income of its month (RF-19, RN-30).
 * The amount is in soles, greater than 0, with at most two decimals. A fine is never edited: it is
 * deleted and created again (RN-24).
 */
public record Fine(
    Long id, Long guestId, BigDecimal amount, YearMonth month, @Nullable String description) {

  /**
   * Year 0 of Java is 1 BC for PostgreSQL, and it reads back as year 1: the months start in year 1.
   * The contract allows four digits at most.
   */
  private static final int MIN_YEAR = 1;

  private static final int MAX_YEAR = 9999;

  /** NUMERIC(10,2): eight digits before the point. */
  private static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999.99");

  public Fine {
    if (!isValidAmount(amount)) {
      throw new IllegalArgumentException("The fine amount is not valid");
    }
    if (!isValidMonth(month)) {
      throw new IllegalArgumentException("The fine month is not valid");
    }
  }

  /** A month that the base stores and reads back as it is: from year 1 to year 9999. */
  public static boolean isValidMonth(YearMonth month) {
    return month.getYear() >= MIN_YEAR && month.getYear() <= MAX_YEAR;
  }

  /** Greater than 0, at most two decimals (trailing zeros do not count) and within the column. */
  public static boolean isValidAmount(BigDecimal amount) {
    return amount.signum() > 0
        && amount.stripTrailingZeros().scale() <= 2
        && amount.compareTo(MAX_AMOUNT) <= 0;
  }

  /**
   * The month that a stored first day stands for.
   *
   * @throws IllegalArgumentException if the date is not the first day of its month
   */
  public static YearMonth monthOf(LocalDate firstDay) {
    if (firstDay.getDayOfMonth() != 1) {
      throw new IllegalArgumentException("A month is stored as its first day");
    }
    return YearMonth.from(firstDay);
  }

  /** The first day of the month, as the month is stored. */
  public LocalDate firstDay() {
    return month.atDay(1);
  }
}
