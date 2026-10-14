package com.tarimatwasi.quipu.contract.port.in;

/** No contract has the requested id. */
public class ContractNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public ContractNotFoundException() {
    super("No contract has the requested id.");
  }
}
