package com.tarimatwasi.quipu.environment.application.port.in;

/**
 * The environment has a contract in force, so it cannot be deactivated or take another (RN-12,
 * RN-20).
 */
public class EnvironmentOccupiedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public EnvironmentOccupiedException() {
    super("The environment has a contract in force");
  }
}
