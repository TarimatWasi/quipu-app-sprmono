package com.tarimatwasi.quipu.auth.port.in;

/** TAR-127: the deactivation would leave the system without an active administrator. */
public class LastActiveAdministratorException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public LastActiveAdministratorException() {
    super("The last active administrator cannot be deactivated");
  }
}
