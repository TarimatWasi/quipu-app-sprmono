package com.tarimatwasi.quipu.payment.application.port.in;

import java.io.Serial;

/** The guest has no contract in force, so it cannot upload a voucher (RN-27). */
public class NoActiveContractException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public NoActiveContractException() {
    super("The guest has no contract in force (RN-27).");
  }
}
