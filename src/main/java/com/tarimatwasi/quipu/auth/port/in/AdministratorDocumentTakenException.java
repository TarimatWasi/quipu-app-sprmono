package com.tarimatwasi.quipu.auth.port.in;

/**
 * RF-22: another account, administrator or guest, already has the document of the new
 * administrator.
 */
public class AdministratorDocumentTakenException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public AdministratorDocumentTakenException() {
    super("Another account already has that document");
  }
}
