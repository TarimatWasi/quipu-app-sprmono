package com.tarimatwasi.quipu.expense.application.port.out;

import com.tarimatwasi.quipu.expense.domain.Expense;
import com.tarimatwasi.quipu.expense.domain.ExpenseCategory;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.InvalidCursorException;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

public interface ExpenseRepositoryPort {

  /** Stores a new expense. */
  Expense insert(
      ExpenseCategory category, BigDecimal amount, YearMonth month, @Nullable String description);

  /** Replaces the data of the expense; empty if there is none with that id. */
  Optional<Expense> update(
      Long id,
      ExpenseCategory category,
      BigDecimal amount,
      YearMonth month,
      @Nullable String description);

  /** Deletes the expense; false if there is none with that id. */
  boolean delete(Long id);

  /**
   * One page of the expenses of the month, oldest first.
   *
   * @throws InvalidCursorException if the cursor of the page is not one this service issued
   */
  CursorPage<Expense> findByMonth(YearMonth month, PageQuery page);

  /** The sum of the amounts of the expenses of the month, added up by the base; 0 if none. */
  BigDecimal sumByMonth(YearMonth month);
}
