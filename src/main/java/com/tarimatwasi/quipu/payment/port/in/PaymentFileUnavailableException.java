package com.tarimatwasi.quipu.payment.port.in;

/** The file store of the vouchers is not configured or did not answer; nothing was changed. */
public class PaymentFileUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception with the failure of the store. */
  public PaymentFileUnavailableException(Throwable cause) {
    super("The voucher file store is unavailable", cause);
  }
}
