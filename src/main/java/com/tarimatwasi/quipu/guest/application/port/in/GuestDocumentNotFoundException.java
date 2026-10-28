package com.tarimatwasi.quipu.guest.application.port.in;

/**
 * The guest does not exist, the document is not one of that guest, or another action on it won: one
 * answer for all, so the caller cannot tell which (RN-37).
 */
public class GuestDocumentNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public GuestDocumentNotFoundException() {
    super("The guest document does not exist");
  }
}
