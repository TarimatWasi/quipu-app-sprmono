package com.tarimatwasi.quipu.auth.application.port.in;

import java.io.Serial;

/**
 * The new password is the same as the current one, which defeats the forced change (RF-12) and the
 * change from the account menu (A0.6, TAR-127).
 */
public class PasswordUnchangedException extends RuntimeException {
  @Serial private static final long serialVersionUID = 1L;

  private final boolean firstAccess;

  /**
   * Creates the exception.
   *
   * @param firstAccess the change replaces a temporary password (RF-12): the message names it
   */
  public PasswordUnchangedException(boolean firstAccess) {
    super("The new password must differ from the current one");
    this.firstAccess = firstAccess;
  }

  /** Whether the change replaces a temporary password, as in the first access. */
  public boolean firstAccess() {
    return firstAccess;
  }
}
