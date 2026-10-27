package com.tarimatwasi.quipu.fine.port.in;

/** The month is outside the years that the base stores as they are (1 to 9999). */
public class InvalidFineMonthException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public InvalidFineMonthException() {
    super("The fine month is not valid");
  }
}
