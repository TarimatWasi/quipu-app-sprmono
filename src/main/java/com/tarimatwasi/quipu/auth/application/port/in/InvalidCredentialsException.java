package com.tarimatwasi.quipu.auth.application.port.in;

import java.io.Serial;

/** Unknown document or wrong password: the same error for both, so it does not reveal which. */
public class InvalidCredentialsException extends RuntimeException {
  @Serial private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public InvalidCredentialsException() {
    super("Invalid document or password");
  }
}
