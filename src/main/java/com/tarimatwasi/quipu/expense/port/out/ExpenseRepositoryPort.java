package com.tarimatwasi.quipu.expense.port.out;

import com.tarimatwasi.quipu.expense.domain.Expense;
import com.tarimatwasi.quipu.expense.domain.ExpenseCategory;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public interface ExpenseRepositoryPort {

  /** Stores a new expense. */
  Expense insert(
      ExpenseCategory category, BigDecimal amount, YearMonth month, @Nullable String description);

  /** Replaces the data of the expense; empty if there is none with that id. */
  Optional<Expense> update(
      UUID id,
      ExpenseCategory category,
      BigDecimal amount,
      YearMonth month,
      @Nullable String description);

  /** Deletes the expense; false if there is none with that id. */
  boolean delete(UUID id);

  /** The expenses of the month, oldest first. */
  List<Expense> findByMonth(YearMonth month);
}
