package com.tarimatwasi.quipu.environment.application.port.in;

import java.io.Serial;

/** No environment has the requested id. */
public class EnvironmentNotFoundException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public EnvironmentNotFoundException() {
    super("Environment not found");
  }
}
