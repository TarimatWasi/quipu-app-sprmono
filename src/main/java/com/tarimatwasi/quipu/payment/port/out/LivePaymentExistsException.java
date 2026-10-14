package com.tarimatwasi.quipu.payment.port.out;

import com.tarimatwasi.quipu.payment.domain.PaymentStatus;

/**
 * The period or the stay already has a live payment (the unique index of the table said so). The
 * status says which one, so that the answer is the right conflict.
 */
public class LivePaymentExistsException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final PaymentStatus liveStatus;

  /** Creates the exception with the status of the payment that is already there. */
  public LivePaymentExistsException(PaymentStatus liveStatus) {
    super("A " + liveStatus + " payment already covers it");
    this.liveStatus = liveStatus;
  }

  /** PENDING or APPROVED. */
  public PaymentStatus liveStatus() {
    return liveStatus;
  }
}
