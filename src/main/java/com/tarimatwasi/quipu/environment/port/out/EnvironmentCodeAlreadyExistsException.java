package com.tarimatwasi.quipu.environment.port.out;

/** Another environment already has the code. */
public class EnvironmentCodeAlreadyExistsException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception with the cause the database reported. */
  public EnvironmentCodeAlreadyExistsException(Throwable cause) {
    super("Another environment already has that code", cause);
  }
}
