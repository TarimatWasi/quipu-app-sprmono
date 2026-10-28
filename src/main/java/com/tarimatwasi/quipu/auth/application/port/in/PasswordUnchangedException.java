package com.tarimatwasi.quipu.auth.application.port.in;

/** The new password is the same as the current one, which defeats the forced change (RF-12). */
public class PasswordUnchangedException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public PasswordUnchangedException() {
    super("The new password must differ from the current one");
  }
}
