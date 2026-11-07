package com.tarimatwasi.quipu.guest.application.port.in;

import java.io.Serial;

/** Only guests of type CONTRACT take a contract (RN-13). */
public class GuestNotContractException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public GuestNotContractException() {
    super("Only guests with a contract take a contract");
  }
}
