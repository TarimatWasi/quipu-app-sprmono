package com.tarimatwasi.quipu.expense.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.expense.domain.Expense;
import com.tarimatwasi.quipu.expense.domain.ExpenseCategory;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.TestTables;
import java.math.BigDecimal;
import java.time.YearMonth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** RN-24 (TAR-171): the version guards the edits, never the deletion of an expense. */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@ImportTestcontainers(PostgresContainers.class)
class ExpenseRepositoryAdapterTest {

  @Autowired ExpenseRepositoryAdapter adapter;
  @Autowired ExpenseJpaRepository repository;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate transaction;

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
  }

  private Expense inserted() {
    return adapter.insert(
        ExpenseCategory.WATER, new BigDecimal("10.00"), YearMonth.of(2026, 9), null);
  }

  @Test
  void delete_ofAnExpenseEditedAfterItWasRead_stillDeletesIt() {
    Expense expense = inserted();

    boolean deleted =
        Boolean.TRUE.equals(
            transaction.execute(
                status -> {
                  // Read first (the persistence context keeps this version) ...
                  repository.findById(expense.id());
                  // ... and someone else edits it before the delete.
                  jdbc.update(
                      "UPDATE expenses SET version = version + 1 WHERE id = ?", expense.id());
                  return adapter.delete(expense.id());
                }));

    assertThat(deleted).isTrue();
    assertThat(repository.findById(expense.id())).isEmpty();
  }

  @Test
  void delete_ofAMissingExpense_returnsFalse() {
    // As in production, where the application service opens the transaction (BE-SPR-APP-02).
    Boolean deleted = transaction.execute(status -> adapter.delete(987_654L));

    assertThat(deleted).isFalse();
  }
}
