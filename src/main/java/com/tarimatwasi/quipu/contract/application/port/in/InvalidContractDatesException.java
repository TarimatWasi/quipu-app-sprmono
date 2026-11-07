package com.tarimatwasi.quipu.contract.application.port.in;

import java.io.Serial;

/** The end date of a contract is not after its start date (RF-03). */
public class InvalidContractDatesException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public InvalidContractDatesException() {
    super("The end date of a contract is not after its start date (RF-03).");
  }
}
