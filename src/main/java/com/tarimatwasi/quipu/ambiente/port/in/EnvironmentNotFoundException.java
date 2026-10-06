package com.tarimatwasi.quipu.ambiente.port.in;

/** No environment has the requested id. */
public class EnvironmentNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public EnvironmentNotFoundException() {
    super("Environment not found");
  }
}
