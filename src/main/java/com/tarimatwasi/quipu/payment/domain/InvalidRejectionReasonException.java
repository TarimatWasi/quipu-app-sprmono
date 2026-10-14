package com.tarimatwasi.quipu.payment.domain;

/** A rejection needs a reason that is not blank and has at most 500 characters (RN-05). */
public class InvalidRejectionReasonException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public InvalidRejectionReasonException() {
    super("A rejection needs a reason of 1 to 500 characters");
  }
}
