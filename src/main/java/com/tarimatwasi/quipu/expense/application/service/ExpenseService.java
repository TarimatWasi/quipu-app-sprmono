package com.tarimatwasi.quipu.expense.application.service;

import com.tarimatwasi.quipu.expense.application.port.in.ExpenseNotFoundException;
import com.tarimatwasi.quipu.expense.application.port.in.InvalidExpenseDescriptionException;
import com.tarimatwasi.quipu.expense.application.port.in.InvalidExpenseException;
import com.tarimatwasi.quipu.expense.application.port.in.InvalidExpenseMonthException;
import com.tarimatwasi.quipu.expense.application.port.in.ManageExpensesUseCase;
import com.tarimatwasi.quipu.expense.application.port.out.ExpenseRepositoryPort;
import com.tarimatwasi.quipu.expense.domain.Expense;
import com.tarimatwasi.quipu.expense.domain.ExpenseCategory;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import java.math.BigDecimal;
import java.time.YearMonth;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@PreAuthorize("hasRole('ADMIN')") // BE-SPR-SEC-02 (TAR-170): admin use case, besides the URL rule
public class ExpenseService implements ManageExpensesUseCase {

  /** NUMERIC(10,2): eight digits before the point. */
  private static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999.99");

  /** The limit of the contract, in characters (code points) and not in UTF-16 units. */
  private static final int MAX_DESCRIPTION = 500;

  private final ExpenseRepositoryPort expenses;

  ExpenseService(ExpenseRepositoryPort expenses) {
    this.expenses = expenses;
  }

  @Override
  @Transactional
  public ExpenseView create(ExpenseCommand command) {
    validate(command.amount());
    requireValid(command.month());
    return view(
        expenses.insert(
            category(command.category()),
            command.amount(),
            command.month(),
            description(command.description())));
  }

  @Override
  @Transactional
  public ExpenseView update(Long id, ExpenseCommand command) {
    validate(command.amount());
    requireValid(command.month());
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
  public void delete(Long id) {
    if (!expenses.delete(id)) {
      throw new ExpenseNotFoundException();
    }
  }

  @Override
  @Transactional(readOnly = true)
  public CursorPage<ExpenseView> list(YearMonth month, PageQuery page) {
    requireValid(month);
    return expenses.findByMonth(month, page).map(ExpenseService::view);
  }

  @Override
  @Transactional(readOnly = true)
  public BigDecimal totalOf(YearMonth month) {
    requireValid(month);
    return expenses.sumByMonth(month);
  }

  private static void validate(BigDecimal amount) {
    if (amount.signum() <= 0
        || amount.stripTrailingZeros().scale() > 2
        || amount.compareTo(MAX_AMOUNT) > 0) {
      throw new InvalidExpenseException();
    }
  }

  /** Year 0 matches the pattern, but the base keeps it as 1 BC and reads it back as year 1. */
  private static void requireValid(YearMonth month) {
    if (!Expense.isValidMonth(month)) {
      throw new InvalidExpenseMonthException();
    }
  }

  private static @Nullable String description(@Nullable String description) {
    if (description == null || description.isBlank()) {
      return null;
    }
    // The limit of the contract applies to what the caller sent, before the edges are stripped
    if (description.codePointCount(0, description.length()) > MAX_DESCRIPTION) {
      throw new InvalidExpenseDescriptionException();
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
