package com.tarimatwasi.quipu.auth.application.port.in;

/**
 * TAR-127 (A0.5): an administrator without a name yet activates its account with the code of its
 * email, and its full name is required while the account has none. It holds for the initial
 * administrator (ADR-F2) and for one created by another (RF-22) alike (TAR-188). The code is not
 * consumed, so the administrator can send it again with the name.
 */
public class AdministratorNameRequiredException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public AdministratorNameRequiredException() {
    super("The administrator must write its full name to activate the account");
  }
}
