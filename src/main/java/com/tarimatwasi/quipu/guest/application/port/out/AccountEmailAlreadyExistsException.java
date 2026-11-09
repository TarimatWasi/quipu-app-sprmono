package com.tarimatwasi.quipu.guest.application.port.out;

import java.io.Serial;

/**
 * TAR-229: another access account, administrator or guest, already has that account email, ignoring
 * case.
 */
public class AccountEmailAlreadyExistsException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the exception with the cause the account module reported. */
  public AccountEmailAlreadyExistsException(Throwable cause) {
    super("Another account already has that email", cause);
  }
}
