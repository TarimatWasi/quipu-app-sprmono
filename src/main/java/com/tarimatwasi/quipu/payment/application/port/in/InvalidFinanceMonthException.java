package com.tarimatwasi.quipu.payment.application.port.in;

/** The year of the month of a balance is not from 1 to 9999, such as year 0000 (TAR-48). */
public class InvalidFinanceMonthException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public InvalidFinanceMonthException() {
    super("The month is outside the years 1 to 9999.");
  }
}
