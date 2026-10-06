package com.tarimatwasi.quipu.environment.port.in;

/** Another environment already has the code (RF-01). */
public class EnvironmentCodeTakenException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception with the cause the store reported. */
  public EnvironmentCodeTakenException(Throwable cause) {
    super("Another environment already has that code", cause);
  }
}
