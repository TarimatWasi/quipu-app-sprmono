package com.tarimatwasi.quipu.auth.port.in;

/** The account is temporarily locked after too many consecutive failed logins (SEG-06). */
public class AccountLockedException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public AccountLockedException() {
    super("The account is temporarily locked");
  }
}
