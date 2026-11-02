package com.tarimatwasi.quipu.payment.application.port.out;

import com.tarimatwasi.quipu.payment.domain.MonthTotal;
import java.time.YearMonth;

/** The fines of a month, as the balance needs them (RF-18, RN-30). */
public interface PaymentFinePort {

  /** The sum of the fines of the month, of any guest, and their count; 0 if it has none. */
  MonthTotal totalOf(YearMonth month);
}
