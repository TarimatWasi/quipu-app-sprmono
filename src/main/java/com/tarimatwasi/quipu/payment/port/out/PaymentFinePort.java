package com.tarimatwasi.quipu.payment.port.out;

import java.math.BigDecimal;
import java.time.YearMonth;

/** The fines of a month, as the balance needs them (RF-18, RN-30). */
public interface PaymentFinePort {

  /** The sum of the fines of the month, of any guest; 0 if it has none. */
  BigDecimal totalOf(YearMonth month);
}
