package com.tarimatwasi.quipu.guest.application.port.in;

import java.io.Serial;

/** There is no stay with that id for that guest. */
public class StayNotFoundException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public StayNotFoundException() {
    super("The stay does not exist");
  }
}
