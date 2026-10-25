package com.tarimatwasi.quipu.guest.port.out;

/** The guest already has a consent of that version (D-49). */
public class GuestConsentAlreadyExistsException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception with the cause the database reported. */
  public GuestConsentAlreadyExistsException(Throwable cause) {
    super("The guest already accepted that version", cause);
  }
}
