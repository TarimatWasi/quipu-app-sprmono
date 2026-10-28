package com.tarimatwasi.quipu.environment.application.port.in;

/** The code, without its surrounding whitespace, is empty or has characters it cannot have. */
public class InvalidEnvironmentCodeException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public InvalidEnvironmentCodeException() {
    super("The environment code is empty");
  }
}
