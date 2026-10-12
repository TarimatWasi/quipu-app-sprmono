package com.tarimatwasi.quipu.expense.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/** BE-SPR-DAT-13 (TAR-171): two simultaneous edits of one expense do not overwrite each other. */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@ImportTestcontainers(PostgresContainers.class)
class ExpenseJpaEntityTest {

  @Autowired ExpenseJpaRepository repository;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
  }

  @Test
  void update_withAStaleCopy_failsWithAnOptimisticLockConflict() {
    Long id =
        repository
            .save(
                new ExpenseJpaEntity(
                    ExpenseCategory.WATER, new BigDecimal("10.00"), YearMonth.of(2026, 9), null))
            .getId();
    // Outside a transaction each read is a detached copy, as with two simultaneous requests.
    ExpenseJpaEntity first = repository.findById(id).orElseThrow();
    ExpenseJpaEntity second = repository.findById(id).orElseThrow();
    first.replace(ExpenseCategory.WATER, new BigDecimal("20.00"), YearMonth.of(2026, 9), null);
    second.replace(ExpenseCategory.WATER, new BigDecimal("30.00"), YearMonth.of(2026, 9), null);

    repository.save(first);

    assertThatThrownBy(() -> repository.save(second))
        .isInstanceOf(OptimisticLockingFailureException.class);
    assertThat(repository.findById(id).orElseThrow().toDomain().amount())
        .isEqualByComparingTo("20.00");
  }
}
