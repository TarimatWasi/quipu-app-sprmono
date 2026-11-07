package com.tarimatwasi.quipu.payment.application.port.in;

import java.io.Serial;

/** No payment has the requested id. */
public class PaymentNotFoundException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public PaymentNotFoundException() {
    super("No payment has the requested id.");
  }
}
