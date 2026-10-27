package com.tarimatwasi.quipu.fine.port.in;

/** The guest of a new fine does not exist; the message names no guest. */
public class FineGuestNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public FineGuestNotFoundException() {
    super("The guest of the fine does not exist");
  }
}
