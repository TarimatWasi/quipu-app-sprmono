package com.tarimatwasi.quipu.guest.application.port.in;

import java.io.Serial;

/** A guest update with no field to change. */
public class EmptyGuestUpdateException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public EmptyGuestUpdateException() {
    super("An update needs at least one field");
  }
}
