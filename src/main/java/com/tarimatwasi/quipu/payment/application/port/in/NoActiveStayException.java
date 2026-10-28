package com.tarimatwasi.quipu.payment.application.port.in;

/** The temporary guest has no stay in course nor to come, so it cannot upload a voucher (CU-22). */
public class NoActiveStayException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public NoActiveStayException() {
    super("The guest has no stay in course nor to come (CU-22).");
  }
}
