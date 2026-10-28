package com.tarimatwasi.quipu.payment.application.port.in;

/** The file is not a JPG, PNG or PDF by content, or is over 5 MB (RF-15). */
public class InvalidVoucherFileException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public InvalidVoucherFileException() {
    super("The file is not a JPG, PNG or PDF by content, or is over 5 MB (RF-15).");
  }
}
