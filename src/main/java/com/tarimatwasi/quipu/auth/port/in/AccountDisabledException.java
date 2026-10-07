package com.tarimatwasi.quipu.auth.port.in;

/** The account is closed (inactive): it cannot sign in or change its password. */
public class AccountDisabledException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public AccountDisabledException() {
    super("Account is disabled");
  }
}
