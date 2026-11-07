package com.tarimatwasi.quipu.contract.application.port.in;

import java.io.Serial;

/** No contract has the requested id. */
public class ContractNotFoundException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public ContractNotFoundException() {
    super("No contract has the requested id.");
  }
}
