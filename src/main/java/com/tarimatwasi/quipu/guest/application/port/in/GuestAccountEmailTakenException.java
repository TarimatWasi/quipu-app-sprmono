package com.tarimatwasi.quipu.guest.application.port.in;

import java.io.Serial;

/**
 * TAR-229, TAR-349 (RF-02, D-77): another access account, administrator or guest, already has that
 * email as its account email, ignoring case.
 */
public class GuestAccountEmailTakenException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the exception with the cause the account module reported. */
  public GuestAccountEmailTakenException(Throwable cause) {
    super("Another account already has that email", cause);
  }
}
