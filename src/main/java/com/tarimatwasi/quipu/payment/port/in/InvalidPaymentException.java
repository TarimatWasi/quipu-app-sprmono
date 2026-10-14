package com.tarimatwasi.quipu.payment.port.in;

/** A field of a payment is out of range, or the choice of period or stay is wrong. */
public class InvalidPaymentException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String field;

  /** Creates the exception for the field that is not valid. */
  public InvalidPaymentException(String field) {
    super("Invalid payment field: " + field);
    this.field = field;
  }

  /** The name of the field, as the contract names it. */
  public String field() {
    return field;
  }
}
