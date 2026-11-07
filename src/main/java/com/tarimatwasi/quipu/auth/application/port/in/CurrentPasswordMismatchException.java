package com.tarimatwasi.quipu.auth.application.port.in;

import java.io.Serial;

/**
 * TAR-127 (A0.6): the current password given to change the password is wrong. The session is valid;
 * what failed is the confirmation of the password, so it is not the 401 of the login.
 */
public class CurrentPasswordMismatchException extends RuntimeException {
  @Serial private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public CurrentPasswordMismatchException() {
    super("The current password is wrong");
  }
}
