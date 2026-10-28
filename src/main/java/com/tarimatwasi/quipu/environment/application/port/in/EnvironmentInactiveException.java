package com.tarimatwasi.quipu.environment.application.port.in;

/** The environment is inactive, so it takes no new contract (RF-01, TAR-200). */
public class EnvironmentInactiveException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public EnvironmentInactiveException() {
    super("The environment is inactive");
  }
}
