package com.tarimatwasi.quipu.guest.application.port.in;

/** The file store of the guest documents is not configured or did not answer; nothing changed. */
public class GuestDocumentUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception with the failure of the store. */
  public GuestDocumentUnavailableException(Throwable cause) {
    super("The guest document store is unavailable", cause);
  }
}
