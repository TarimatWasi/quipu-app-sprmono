package com.tarimatwasi.quipu.fine.application.port.in;

/** The amount is not greater than 0, has more than two decimals or does not fit the column. */
public class InvalidFineException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public InvalidFineException() {
    super("The fine amount is not valid");
  }
}
