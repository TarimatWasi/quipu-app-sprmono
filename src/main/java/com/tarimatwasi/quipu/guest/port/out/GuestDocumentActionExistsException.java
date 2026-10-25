package com.tarimatwasi.quipu.guest.port.out;

/** The document already has its action: another deletion or replacement of it won. */
public class GuestDocumentActionExistsException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception with the failed insert. */
  public GuestDocumentActionExistsException(Throwable cause) {
    super("The guest document already has an action", cause);
  }
}
