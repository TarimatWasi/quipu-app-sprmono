package com.tarimatwasi.quipu.payment.port.in;

/** The payment is no longer pending: someone resolved it first. */
public class PaymentAlreadyResolvedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public PaymentAlreadyResolvedException() {
    super("The payment is no longer pending: someone resolved it first.");
  }
}
