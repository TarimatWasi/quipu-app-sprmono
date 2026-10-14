package com.tarimatwasi.quipu.contract.port.out;

/** The file store (R2) is not configured or did not answer. */
public class ContractFileStorageException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception with the reason. */
  public ContractFileStorageException(String message, Throwable cause) {
    super(message, cause);
  }

  /** Creates the exception when there is no underlying failure. */
  public ContractFileStorageException(String message) {
    super(message);
  }
}
