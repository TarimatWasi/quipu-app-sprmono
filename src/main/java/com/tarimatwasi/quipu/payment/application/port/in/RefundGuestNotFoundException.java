package com.tarimatwasi.quipu.payment.application.port.in;

/** The guest of a refund does not exist (TAR-240); answered as RESOURCE_NOT_FOUND. */
public class RefundGuestNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public RefundGuestNotFoundException() {
    super("The guest of the refund does not exist");
  }
}
