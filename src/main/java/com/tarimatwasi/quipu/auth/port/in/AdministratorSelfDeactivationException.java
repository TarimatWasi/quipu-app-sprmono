package com.tarimatwasi.quipu.auth.port.in;

/** TAR-127 (D-70 a): an administrator cannot deactivate their own account. */
public class AdministratorSelfDeactivationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public AdministratorSelfDeactivationException() {
    super("An administrator cannot deactivate their own account");
  }
}
