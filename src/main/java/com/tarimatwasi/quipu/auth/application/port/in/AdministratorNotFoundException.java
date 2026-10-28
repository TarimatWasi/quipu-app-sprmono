package com.tarimatwasi.quipu.auth.application.port.in;

/** TAR-127: the id does not exist or is not of an ADMIN account; both answer alike. */
public class AdministratorNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public AdministratorNotFoundException() {
    super("The administrator does not exist");
  }
}
