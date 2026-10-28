package com.tarimatwasi.quipu.expense.application.port.in;

/** The year of the month is not from 1 to 9999, such as year 0000 (TAR-221). */
public class InvalidExpenseMonthException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public InvalidExpenseMonthException() {
    super("The expense month is not valid");
  }
}
