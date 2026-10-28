package com.tarimatwasi.quipu.contract.application.port.in;

/** The file of a contract is not a PDF by content, or is over 10 MB (RF-15). */
public class InvalidContractFileException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public InvalidContractFileException() {
    super("The file of a contract is not a PDF by content, or is over 10 MB (RF-15).");
  }
}
