package com.tarimatwasi.quipu.payment.domain;

import java.io.Serial;

/**
 * The payment cannot be corrected in its status: the guest corrects only a rejected one, the
 * administrator a pending or rejected one (TAR-239).
 */
public class CorrectionNotAllowedException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public CorrectionNotAllowedException() {
    super("The payment cannot be corrected in its status");
  }
}
