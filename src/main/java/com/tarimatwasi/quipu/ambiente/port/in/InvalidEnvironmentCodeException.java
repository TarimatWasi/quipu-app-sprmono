package com.tarimatwasi.quipu.ambiente.port.in;

/** The code has no visible character once its surrounding whitespace is removed. */
public class InvalidEnvironmentCodeException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public InvalidEnvironmentCodeException() {
    super("The environment code is empty");
  }
}
