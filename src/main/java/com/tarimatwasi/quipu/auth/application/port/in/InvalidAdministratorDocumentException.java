package com.tarimatwasi.quipu.auth.application.port.in;

/** The document number of the new administrator does not fit its type (a DNI has 8 digits). */
public class InvalidAdministratorDocumentException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public InvalidAdministratorDocumentException() {
    super("The document number does not fit its type");
  }
}
