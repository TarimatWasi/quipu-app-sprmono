package com.tarimatwasi.quipu.guest.application.port.in;

/** The dates of a stay cross another stay of the same guest. */
public class StayOverlapException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public StayOverlapException() {
    super("The dates cross another stay of the guest");
  }
}
