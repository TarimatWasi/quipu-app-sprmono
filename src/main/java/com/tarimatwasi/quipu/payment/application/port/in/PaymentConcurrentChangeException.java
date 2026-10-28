package com.tarimatwasi.quipu.payment.application.port.in;

/**
 * Another request changed what this one was deciding on (the payment that blocked a period is gone
 * by the time it is looked for): nothing was registered, and trying again gives the right answer.
 */
public class PaymentConcurrentChangeException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public PaymentConcurrentChangeException() {
    super("The payment changed while it was being registered");
  }
}
