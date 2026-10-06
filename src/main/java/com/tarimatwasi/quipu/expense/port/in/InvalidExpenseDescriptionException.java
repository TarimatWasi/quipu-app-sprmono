package com.tarimatwasi.quipu.expense.port.in;

/** The description has more than 500 characters, counted as Unicode code points. */
public class InvalidExpenseDescriptionException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public InvalidExpenseDescriptionException() {
    super("The expense description is too long");
  }
}
