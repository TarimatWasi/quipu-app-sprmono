package com.tarimatwasi.quipu.guest.port.in;

/** There is no stay with that id for that guest. */
public class StayNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public StayNotFoundException() {
    super("The stay does not exist");
  }
}
