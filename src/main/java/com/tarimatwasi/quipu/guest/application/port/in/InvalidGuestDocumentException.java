package com.tarimatwasi.quipu.guest.application.port.in;

import java.io.Serial;

/** The document number does not fit its type (a DNI has 8 digits). */
public class InvalidGuestDocumentException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public InvalidGuestDocumentException() {
    super("The document number is not valid for its type");
  }
}
