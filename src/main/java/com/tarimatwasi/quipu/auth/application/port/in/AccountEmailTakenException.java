package com.tarimatwasi.quipu.auth.application.port.in;

/**
 * TAR-229: another account, administrator or guest, already has that account email, ignoring case.
 * A guest contact email is not an account email (D-33).
 */
public class AccountEmailTakenException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception with the cause the store reported. */
  public AccountEmailTakenException(Throwable cause) {
    super("Another account already has that email", cause);
  }
}
