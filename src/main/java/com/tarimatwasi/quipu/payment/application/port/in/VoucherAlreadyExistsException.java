package com.tarimatwasi.quipu.payment.application.port.in;

import java.io.Serial;

/** A voucher is still pending for the period or the stay (RN-15). */
public class VoucherAlreadyExistsException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public VoucherAlreadyExistsException() {
    super("A voucher is still pending for the period or the stay (RN-15).");
  }
}
