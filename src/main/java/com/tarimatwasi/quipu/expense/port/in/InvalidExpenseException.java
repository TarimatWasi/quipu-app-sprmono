package com.tarimatwasi.quipu.expense.port.in;

/** The amount is not greater than 0, has more than two decimals or does not fit the column. */
public class InvalidExpenseException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public InvalidExpenseException() {
    super("The expense amount is not valid");
  }
}
