package com.tarimatwasi.quipu.payment.application.port.in;

import java.io.Serial;

/** A rejection needs a reason that is not blank (RN-05). */
public class PaymentReasonRequiredException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public PaymentReasonRequiredException() {
    super("A rejection needs a reason that is not blank (RN-05).");
  }
}
