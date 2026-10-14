package com.tarimatwasi.quipu.payment.port.in;

/** A voucher is still pending for the period or the stay (RN-15). */
public class VoucherAlreadyExistsException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public VoucherAlreadyExistsException() {
    super("A voucher is still pending for the period or the stay (RN-15).");
  }
}
