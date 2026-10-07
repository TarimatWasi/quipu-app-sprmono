package com.tarimatwasi.quipu.guest.port.in;

/** No guest has the requested id. */
public class GuestNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public GuestNotFoundException() {
    super("Guest not found");
  }
}
