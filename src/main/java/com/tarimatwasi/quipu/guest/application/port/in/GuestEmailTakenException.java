package com.tarimatwasi.quipu.guest.application.port.in;

/** Another guest already has that contact email (D-33). */
public class GuestEmailTakenException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception with the cause the store reported. */
  public GuestEmailTakenException(Throwable cause) {
    super("Another guest already has that email", cause);
  }
}
