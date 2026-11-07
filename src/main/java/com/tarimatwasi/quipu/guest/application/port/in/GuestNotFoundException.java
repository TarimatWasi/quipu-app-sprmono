package com.tarimatwasi.quipu.guest.application.port.in;

import java.io.Serial;

/** No guest has the requested id. */
public class GuestNotFoundException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public GuestNotFoundException() {
    super("Guest not found");
  }
}
