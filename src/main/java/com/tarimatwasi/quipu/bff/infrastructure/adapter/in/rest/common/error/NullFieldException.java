package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error;

/**
 * A body field sent as an explicit {@code null} where the contract does not allow it: a field that
 * may be left out to keep its value cannot be nulled to erase it.
 */
public class NullFieldException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String field;

  /** Creates the exception for the field, named as the contract names it. */
  public NullFieldException(String field) {
    super("Null field: " + field);
    this.field = field;
  }

  /** The name of the field. */
  public String field() {
    return field;
  }
}
