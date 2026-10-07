package com.tarimatwasi.quipu.guest.port.in;

/** Another guest already has that document (RN-18). */
public class GuestDocumentTakenException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception with the cause the store reported. */
  public GuestDocumentTakenException(Throwable cause) {
    super("Another guest already has that document", cause);
  }
}
