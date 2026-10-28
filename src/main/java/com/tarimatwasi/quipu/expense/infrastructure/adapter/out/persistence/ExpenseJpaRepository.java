package com.tarimatwasi.quipu.expense.infrastructure.adapter.out.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
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

  /**
   * TAR-227: where a page cursor stands, if the expense is one of that month. createdDate is mapped
   * as an Instant (the field of AbstractAuditable; only its getter is a LocalDateTime).
   */
  @Query("select e.createdDate from ExpenseJpaEntity e where e.id = :id and e.month = :month")
  Optional<Instant> findCreatedDate(@Param("id") Long id, @Param("month") LocalDate month);
}
