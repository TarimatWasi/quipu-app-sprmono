package com.tarimatwasi.quipu.auth.port.in;

/** Another account already has that document, so the guest account cannot be opened (D-34e). */
public class AccountDocumentTakenException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception with the cause the store reported. */
  public AccountDocumentTakenException(Throwable cause) {
    super("Another account already has that document", cause);
  }
}
