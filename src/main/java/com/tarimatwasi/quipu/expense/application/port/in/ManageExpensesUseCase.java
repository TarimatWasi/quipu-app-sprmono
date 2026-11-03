package com.tarimatwasi.quipu.expense.application.port.in;

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

  /** An expense as the callers see it, with the version an edit sends back (TAR-240). */
  record ExpenseView(
      Long id,
      ExpenseKind category,
      BigDecimal amount,
      YearMonth month,
      @Nullable String description,
      long version) {}

  /** One page of the expenses of a month and how many the month has (A6 «Mostrando X de Y»). */
  record ExpensePage(CursorPage<ExpenseView> page, long totalCount) {}

  /** What the expenses of a month add up to, and how many they are (RF-18, A6). */
  record MonthTotal(BigDecimal amount, long count) {}

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
   * @param version the version the client loaded
   * @throws ExpenseNotFoundException if there is no expense with that id
   * @throws org.springframework.dao.OptimisticLockingFailureException if the expense changed since
   *     the client loaded it (TAR-240, BE-SPR-DAT-13)
   * @throws InvalidExpenseException if the amount is not valid
   * @throws InvalidExpenseMonthException if the year of the month is not from 1 to 9999
   */
  ExpenseView update(Long id, long version, ExpenseCommand command);

  /**
   * Deletes an expense for good (RN-24).
   *
   * @throws ExpenseNotFoundException if there is no expense with that id
   */
  void delete(Long id);

  /**
   * Lists one page of the expenses of the month, oldest first and read by last index
   * (BE-SPR-WEB-08), with how many the month has.
   *
   * @throws InvalidCursorException if the cursor of the page is not one this service issued
   * @throws InvalidExpenseMonthException if the year of the month is not from 1 to 9999
   */
  ExpensePage list(YearMonth month, PageQuery page);

  /**
   * The sum of the amounts of the expenses of the month and how many they are (RF-18); 0 if it has
   * none.
   *
   * @throws InvalidExpenseMonthException if the year of the month is not from 1 to 9999
   */
  MonthTotal totalOf(YearMonth month);
}
