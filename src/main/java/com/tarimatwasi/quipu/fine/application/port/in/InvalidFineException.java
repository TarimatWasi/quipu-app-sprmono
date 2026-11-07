package com.tarimatwasi.quipu.fine.application.port.in;

import java.io.Serial;

/** The amount is not greater than 0, has more than two decimals or does not fit the column. */
public class InvalidFineException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public InvalidFineException() {
    super("The fine amount is not valid");
  }
}
