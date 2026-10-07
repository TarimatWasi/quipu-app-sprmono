package com.tarimatwasi.quipu.guest.port.in;

/** The document number does not fit its type (a DNI has 8 digits). */
public class InvalidGuestDocumentException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public InvalidGuestDocumentException() {
    super("The document number is not valid for its type");
  }
}
