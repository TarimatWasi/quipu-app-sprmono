package com.tarimatwasi.quipu.guest.application.port.in;

/** Another guest, or an administrator account, already has that document (RN-18, D-34e). */
public class GuestDocumentTakenException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception for a document that belongs to an administrator account. */
  public GuestDocumentTakenException() {
    super("An administrator account already has that document");
  }

  /** Creates the exception with the cause the store reported. */
  public GuestDocumentTakenException(Throwable cause) {
    super("Another guest already has that document", cause);
  }
}
