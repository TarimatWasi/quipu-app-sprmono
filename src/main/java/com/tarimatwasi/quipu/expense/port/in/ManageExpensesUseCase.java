package com.tarimatwasi.quipu.expense.port.in;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** RF-07: register, edit, delete and list the operating expenses of a month. */
public interface ManageExpensesUseCase {

  /** What an expense paid for. */
  enum ExpenseKind {
    WATER,
    ELECTRICITY,
    INTERNET,
    OTHER
  }

  /** An expense as the callers see it. */
  record ExpenseView(
      Long id,
      ExpenseKind category,
      BigDecimal amount,
      YearMonth month,
      @Nullable String description) {}

  /**
   * The data of an expense; an edit replaces all of it. The amount is greater than 0 with at most
   * two decimals. A blank description means none.
   */
  record ExpenseCommand(
      ExpenseKind category, BigDecimal amount, YearMonth month, @Nullable String description) {}

  /**
   * Registers an expense.
   *
   * @throws InvalidExpenseException if the amount is not valid
   */
  ExpenseView create(ExpenseCommand command);

  /**
   * Replaces the data of an expense; nobody audits it (RN-24).
   *
   * @throws ExpenseNotFoundException if there is no expense with that id
   * @throws InvalidExpenseException if the amount is not valid
   */
  ExpenseView update(Long id, ExpenseCommand command);

  /**
   * Deletes an expense for good (RN-24).
   *
   * @throws ExpenseNotFoundException if there is no expense with that id
   */
  void delete(Long id);

  /** Lists the expenses of the month, oldest first. */
  List<ExpenseView> list(YearMonth month);
}
