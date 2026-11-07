package com.tarimatwasi.quipu.auth.application.port.in;

import java.io.Serial;

/** The recovery code does not allow a reset (RF-16): unknown, expired, used or disabled account. */
public class InvalidResetCodeException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the exception; the message is generic on purpose. */
  public InvalidResetCodeException() {
    super("The recovery code is invalid or expired");
  }
}
