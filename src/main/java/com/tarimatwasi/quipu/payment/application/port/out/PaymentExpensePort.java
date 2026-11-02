package com.tarimatwasi.quipu.payment.application.port.out;

import com.tarimatwasi.quipu.payment.domain.MonthTotal;
import java.time.YearMonth;

/** The expenses of a month, as the balance needs them (RF-18, RF-07). */
public interface PaymentExpensePort {

  /** The sum of the expenses of the month and how many they are; 0 if it has none. */
  MonthTotal totalOf(YearMonth month);
}
