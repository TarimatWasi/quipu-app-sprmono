package com.tarimatwasi.quipu.contract.application.port.in;

/** A field of a contract is out of range. */
public class InvalidContractException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String field;

  /** Creates the exception for the field that is out of range. */
  public InvalidContractException(String field) {
    super("Invalid contract field: " + field);
    this.field = field;
  }

  /** The name of the field, as the contract names it. */
  public String field() {
    return field;
  }
}
