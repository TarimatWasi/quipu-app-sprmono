package com.tarimatwasi.quipu.guest.application.port.in;

/** A field of the reason of a deletion or replacement that the rules of RN-37 do not take. */
public class InvalidDocumentActionException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String field;

  /** Creates the exception for the field. */
  public InvalidDocumentActionException(String field) {
    super("Invalid document action field: " + field);
    this.field = field;
  }

  /** The name of the field, as the contract spells it. */
  public String field() {
    return field;
  }
}
