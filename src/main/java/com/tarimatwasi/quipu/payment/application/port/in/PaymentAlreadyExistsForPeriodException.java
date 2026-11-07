package com.tarimatwasi.quipu.payment.application.port.in;

import java.io.Serial;

/** An approved payment already covers the period or the stay (RN-02, RN-03). */
public class PaymentAlreadyExistsForPeriodException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public PaymentAlreadyExistsForPeriodException() {
    super("An approved payment already covers the period or the stay (RN-02, RN-03).");
  }
}
