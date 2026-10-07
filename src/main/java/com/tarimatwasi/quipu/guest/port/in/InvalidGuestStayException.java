package com.tarimatwasi.quipu.guest.port.in;

/**
 * A stay field that is not valid: dates out of order, a bad amount, or stay data on a contract
 * guest.
 */
public class InvalidGuestStayException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String field;

  /** Creates the exception for the field that is not valid. */
  public InvalidGuestStayException(String field) {
    super("The stay field is not valid: " + field);
    this.field = field;
  }

  /** The name of the field that is not valid, as the contract calls it. */
  public String field() {
    return field;
  }
}
