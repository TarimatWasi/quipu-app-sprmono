package com.tarimatwasi.quipu.guest.application.port.out;

/**
 * TAR-234: the deletion of the document is already recorded, by another request at the same time.
 */
public class GuestDocumentDeletionPendingException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception with the failed insert. */
  public GuestDocumentDeletionPendingException(Throwable cause) {
    super("The deletion of the guest document is already recorded", cause);
  }
}
