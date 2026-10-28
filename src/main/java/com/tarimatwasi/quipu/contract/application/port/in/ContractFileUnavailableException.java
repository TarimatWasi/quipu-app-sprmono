package com.tarimatwasi.quipu.contract.application.port.in;

/** The file store of the contracts is not configured or did not answer; nothing was changed. */
public class ContractFileUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception with the failure of the store. */
  public ContractFileUnavailableException(Throwable cause) {
    super("The contract file store is unavailable", cause);
  }
}
