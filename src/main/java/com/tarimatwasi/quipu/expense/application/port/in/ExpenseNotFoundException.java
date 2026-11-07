package com.tarimatwasi.quipu.expense.application.port.in;

import java.io.Serial;

/** No expense has the requested id. */
public class ExpenseNotFoundException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public ExpenseNotFoundException() {
    super("Expense not found");
  }
}
