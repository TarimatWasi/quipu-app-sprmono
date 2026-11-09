package com.tarimatwasi.quipu.auth.application.port.in;

import java.io.Serial;

/**
 * TAR-229: another account, administrator or guest, already has that account email, ignoring case.
 * A guest has one email, the one of its account (TAR-349).
 */
public class AccountEmailTakenException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the exception when the email was found taken before writing it. */
  public AccountEmailTakenException() {
    super("Another account already has that email");
  }

  /** Creates the exception with the cause the store reported. */
  public AccountEmailTakenException(Throwable cause) {
    super("Another account already has that email", cause);
  }
}
