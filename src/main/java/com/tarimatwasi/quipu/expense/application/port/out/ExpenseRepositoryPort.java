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

  /** What the expenses of a month add up to, and how many they are. */
  record Total(BigDecimal amount, long count) {}

  /** Stores a new expense. */
  Expense insert(
      ExpenseCategory category, BigDecimal amount, YearMonth month, @Nullable String description);

  /**
   * Replaces the data of the expense; empty if there is none with that id.
   *
   * @throws org.springframework.dao.OptimisticLockingFailureException if its version is not {@code
   *     version}
   */
  Optional<Expense> update(
      Long id,
      long version,
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

  /** The sum of the amounts of the expenses of the month and their count; 0 if none. */
  Total totalOfMonth(YearMonth month);
}
