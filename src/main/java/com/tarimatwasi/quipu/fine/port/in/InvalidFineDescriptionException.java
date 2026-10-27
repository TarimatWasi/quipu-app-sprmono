package com.tarimatwasi.quipu.fine.port.in;

/** The description has more than 500 characters, counted as Unicode code points. */
public class InvalidFineDescriptionException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public InvalidFineDescriptionException() {
    super("The fine description is too long");
  }
}
