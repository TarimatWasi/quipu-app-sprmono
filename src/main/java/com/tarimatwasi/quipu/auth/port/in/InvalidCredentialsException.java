package com.tarimatwasi.quipu.auth.port.in;

/** Unknown document or wrong password: the same error for both, so it does not reveal which. */
public class InvalidCredentialsException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public InvalidCredentialsException() {
    super("Invalid document or password");
  }
}
