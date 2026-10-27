package com.tarimatwasi.quipu.expense.port.in;

import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.InvalidCursorException;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import java.math.BigDecimal;
import java.time.YearMonth;
import org.jspecify.annotations.Nullable;

/** RF-07: register, edit, delete and list the operating expenses of a month. */
public interface ManageExpensesUseCase {

  /** What an expense paid for. */
  enum ExpenseKind {
    UTILITIES,
    MAINTENANCE,
    CLEANING_SUPPLIES,
    TAXES_FEES
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
   * @throws InvalidExpenseMonthException if the year of the month is not from 1 to 9999
   */
  ExpenseView create(ExpenseCommand command);

  /**
   * Replaces the data of an expense; nobody audits it (RN-24).
   *
   * @throws ExpenseNotFoundException if there is no expense with that id
   * @throws InvalidExpenseException if the amount is not valid
   * @throws InvalidExpenseMonthException if the year of the month is not from 1 to 9999
   */
  ExpenseView update(Long id, ExpenseCommand command);

  /**
   * Deletes an expense for good (RN-24).
   *
   * @throws ExpenseNotFoundException if there is no expense with that id
   */
  void delete(Long id);

  /**
   * Lists one page of the expenses of the month, oldest first and read by last index
   * (BE-SPR-WEB-08).
   *
   * @throws InvalidCursorException if the cursor of the page is not one this service issued
   * @throws InvalidExpenseMonthException if the year of the month is not from 1 to 9999
   */
  CursorPage<ExpenseView> list(YearMonth month, PageQuery page);
}
