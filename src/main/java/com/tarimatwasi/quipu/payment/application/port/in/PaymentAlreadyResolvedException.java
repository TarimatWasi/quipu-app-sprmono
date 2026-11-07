package com.tarimatwasi.quipu.payment.application.port.in;

import java.io.Serial;

/** The payment is no longer pending: someone resolved it first. */
public class PaymentAlreadyResolvedException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public PaymentAlreadyResolvedException() {
    super("The payment is no longer pending: someone resolved it first.");
  }
}
