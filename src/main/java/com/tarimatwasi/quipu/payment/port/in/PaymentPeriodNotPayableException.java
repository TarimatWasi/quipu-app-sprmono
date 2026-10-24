package com.tarimatwasi.quipu.payment.port.in;

/**
 * The contract no longer charges the period of the voucher (it ended or was finished before that
 * month), so it cannot be approved; it can still be rejected (TAR-204).
 */
public class PaymentPeriodNotPayableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public PaymentPeriodNotPayableException() {
    super("The contract no longer charges the period of the payment.");
  }
}
