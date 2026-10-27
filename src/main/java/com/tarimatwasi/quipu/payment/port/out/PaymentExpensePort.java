package com.tarimatwasi.quipu.payment.port.out;

import java.math.BigDecimal;
import java.time.YearMonth;

/** The expenses of a month, as the balance needs them (RF-18, RF-07). */
public interface PaymentExpensePort {

  /** The sum of the expenses of the month; 0 if it has none. */
  BigDecimal totalOf(YearMonth month);
}
