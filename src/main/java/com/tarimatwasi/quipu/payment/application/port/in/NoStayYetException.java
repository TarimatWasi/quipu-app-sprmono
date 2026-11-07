package com.tarimatwasi.quipu.payment.application.port.in;

import java.io.Serial;

/** The temporary guest has no stay at all, so it has no payment status (CU-21). */
public class NoStayYetException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public NoStayYetException() {
    super("The guest has no stay yet (CU-21).");
  }
}
