package com.tarimatwasi.quipu.guest.port.out;

/** Another guest already has the contact email. */
public class GuestEmailAlreadyExistsException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception with the cause the database reported. */
  public GuestEmailAlreadyExistsException(Throwable cause) {
    super("Another guest already has that email", cause);
  }
}
