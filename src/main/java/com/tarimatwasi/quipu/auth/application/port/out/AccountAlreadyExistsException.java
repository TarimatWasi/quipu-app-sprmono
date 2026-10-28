package com.tarimatwasi.quipu.auth.application.port.out;

/** The email or the document number already belongs to another account. */
public class AccountAlreadyExistsException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception with the cause the store reported. */
  public AccountAlreadyExistsException(Throwable cause) {
    super("An account with this email or document number already exists", cause);
  }
}
