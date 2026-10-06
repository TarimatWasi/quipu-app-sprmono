package com.tarimatwasi.quipu.expense.port.in;

/** No expense has the requested id. */
public class ExpenseNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public ExpenseNotFoundException() {
    super("Expense not found");
  }
}
