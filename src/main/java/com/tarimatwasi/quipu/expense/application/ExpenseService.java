package com.tarimatwasi.quipu.expense.application;

import com.tarimatwasi.quipu.expense.domain.Expense;
import com.tarimatwasi.quipu.expense.domain.ExpenseCategory;
import com.tarimatwasi.quipu.expense.port.in.ExpenseNotFoundException;
import com.tarimatwasi.quipu.expense.port.in.InvalidExpenseException;
import com.tarimatwasi.quipu.expense.port.in.ManageExpensesUseCase;
import com.tarimatwasi.quipu.expense.port.out.ExpenseRepositoryPort;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExpenseService implements ManageExpensesUseCase {

  /** NUMERIC(10,2): eight digits before the point. */
  private static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999.99");

  private final ExpenseRepositoryPort expenses;

  ExpenseService(ExpenseRepositoryPort expenses) {
    this.expenses = expenses;
  }

  @Override
  @Transactional
  public ExpenseView create(ExpenseCommand command) {
    validate(command.amount());
    return view(
        expenses.insert(
            category(command.category()),
            command.amount(),
            command.month(),
            description(command.description())));
  }

  @Override
  @Transactional
  public ExpenseView update(UUID id, ExpenseCommand command) {
    validate(command.amount());
    return expenses
        .update(
            id,
            category(command.category()),
            command.amount(),
            command.month(),
            description(command.description()))
        .map(ExpenseService::view)
        .orElseThrow(ExpenseNotFoundException::new);
  }

  @Override
  @Transactional
  public void delete(UUID id) {
    if (!expenses.delete(id)) {
      throw new ExpenseNotFoundException();
    }
  }

  @Override
  @Transactional(readOnly = true)
  public List<ExpenseView> list(YearMonth month) {
    return expenses.findByMonth(month).stream().map(ExpenseService::view).toList();
  }

  private static void validate(BigDecimal amount) {
    if (amount.signum() <= 0
        || amount.stripTrailingZeros().scale() > 2
        || amount.compareTo(MAX_AMOUNT) > 0) {
      throw new InvalidExpenseException();
    }
  }

  private static @Nullable String description(@Nullable String description) {
    if (description == null || description.isBlank()) {
      return null;
    }
    return description.strip();
  }

  private static ExpenseCategory category(ExpenseKind kind) {
    return ExpenseCategory.valueOf(kind.name());
  }

  private static ExpenseView view(Expense expense) {
    return new ExpenseView(
        expense.id(),
        ExpenseKind.valueOf(expense.category().name()),
        expense.amount(),
        expense.month(),
        expense.description());
  }
}
