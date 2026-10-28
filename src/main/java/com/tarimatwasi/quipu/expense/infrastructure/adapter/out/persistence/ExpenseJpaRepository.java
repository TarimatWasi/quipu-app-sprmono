package com.tarimatwasi.quipu.expense.infrastructure.adapter.out.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ExpenseJpaRepository
    extends JpaRepository<ExpenseJpaEntity, Long>, JpaSpecificationExecutor<ExpenseJpaEntity> {

  /**
   * Deletes by id with no version check (RN-24): the version guards the edits, and a delete must
   * not fail because someone edited the expense a moment before. Returns the rows deleted.
   */
  @Modifying
  @Query("delete from ExpenseJpaEntity e where e.id = :id")
  int deleteByIdIgnoringVersion(@Param("id") Long id);

  /** RF-18: read by the prefix of ix_expenses_month_created_date_id; null when there are none. */
  @Query("select sum(e.amount) from ExpenseJpaEntity e where e.month = :month")
  @Nullable BigDecimal sumAmountByMonth(@Param("month") LocalDate month);
}
