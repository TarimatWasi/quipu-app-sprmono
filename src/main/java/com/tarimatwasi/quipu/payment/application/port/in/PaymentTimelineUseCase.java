package com.tarimatwasi.quipu.payment.application.port.in;

import java.time.LocalDate;

/** What a business day makes true about the charges: due dates reached and periods overdue. */
public interface PaymentTimelineUseCase {

  /** States the due and overdue charges of the day; stating them again changes nothing. */
  void sweep(LocalDate day);
}
