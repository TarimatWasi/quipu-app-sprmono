package com.tarimatwasi.quipu.payment.domain;

/** The payment was already approved or rejected, and a payment is resolved only once. */
public class PaymentAlreadyResolvedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public PaymentAlreadyResolvedException() {
    super("The payment is already resolved");
  }
}
