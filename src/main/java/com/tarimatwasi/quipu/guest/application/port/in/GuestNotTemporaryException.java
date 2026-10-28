package com.tarimatwasi.quipu.guest.application.port.in;

/** Only temporary guests have stays (RN-13). */
public class GuestNotTemporaryException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public GuestNotTemporaryException() {
    super("Only temporary guests have stays");
  }
}
