package com.tarimatwasi.quipu.payment.application.port.in;

/**
 * The guest of the session is of the other type: a temporary guest on a route of the guests with a
 * contract, or the reverse (RN-09).
 */
public class OtherGuestTypeException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public OtherGuestTypeException() {
    super("The guest is of the other type (RN-09).");
  }
}
