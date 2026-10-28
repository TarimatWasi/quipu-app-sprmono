package com.tarimatwasi.quipu.auth.application.port.in;

/**
 * RF-22: another account already has the email of the new administrator, ignoring case. A guest
 * contact email is not an account email (D-33).
 */
public class AdministratorEmailTakenException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public AdministratorEmailTakenException() {
    super("Another account already has that email");
  }
}
