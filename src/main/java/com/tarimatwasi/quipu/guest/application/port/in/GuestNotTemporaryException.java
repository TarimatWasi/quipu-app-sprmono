package com.tarimatwasi.quipu.guest.application.port.in;

import java.io.Serial;

/** Only temporary guests have stays (RN-13). */
public class GuestNotTemporaryException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public GuestNotTemporaryException() {
    super("Only temporary guests have stays");
  }
}
