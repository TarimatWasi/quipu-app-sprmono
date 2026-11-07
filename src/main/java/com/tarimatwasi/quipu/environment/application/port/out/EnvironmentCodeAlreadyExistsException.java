package com.tarimatwasi.quipu.environment.application.port.out;

import java.io.Serial;

/** Another environment already has the code. */
public class EnvironmentCodeAlreadyExistsException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the exception with the cause the database reported. */
  public EnvironmentCodeAlreadyExistsException(Throwable cause) {
    super("Another environment already has that code", cause);
  }
}
