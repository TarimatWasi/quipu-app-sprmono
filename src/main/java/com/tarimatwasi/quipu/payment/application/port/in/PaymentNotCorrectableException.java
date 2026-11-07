package com.tarimatwasi.quipu.payment.application.port.in;

import java.io.Serial;

/**
 * PAYMENT_NOT_CORRECTABLE: the guest corrects only a rejected payment, the administrator a pending
 * or rejected one (TAR-239).
 */
public class PaymentNotCorrectableException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public PaymentNotCorrectableException() {
    super("The payment cannot be corrected in its status");
  }
}
