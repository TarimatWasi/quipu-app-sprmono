package com.tarimatwasi.quipu.payment.port.in;

import java.math.BigDecimal;
import java.time.YearMonth;

/** RF-18, CU-08: the income against the expenses of a month; only the administrator reads it. */
public interface FinanceSummaryUseCase {

  /** The balance of a month, in soles with two decimals. */
  record FinanceSummaryView(
      YearMonth month,
      BigDecimal incomeFromPayments,
      BigDecimal incomeFromFines,
      BigDecimal totalIncome,
      BigDecimal totalExpenses,
      BigDecimal net) {}

  /**
   * The balance of the month. A contract payment counts in its period and a stay payment in the
   * month its stay starts; only approved payments count. Guests and environments count whether
   * active or inactive. A month without movements is all zeros.
   *
   * @throws InvalidFinanceMonthException if the year of the month is not from 1 to 9999
   */
  FinanceSummaryView summary(YearMonth month);
}
