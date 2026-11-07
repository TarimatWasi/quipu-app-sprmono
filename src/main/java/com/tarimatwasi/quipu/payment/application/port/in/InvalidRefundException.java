package com.tarimatwasi.quipu.payment.application.port.in;

import java.io.Serial;

/**
 * A field of a refund is out of range, or its amount is more than the pending credit of the guest
 * (RN-38, TAR-240).
 */
public class InvalidRefundException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final String field;

  /** Creates the exception for the field that is not valid. */
  public InvalidRefundException(String field) {
    super("Invalid refund field: " + field);
    this.field = field;
  }

  /** The name of the field, as the contract names it. */
  public String field() {
    return field;
  }
}
