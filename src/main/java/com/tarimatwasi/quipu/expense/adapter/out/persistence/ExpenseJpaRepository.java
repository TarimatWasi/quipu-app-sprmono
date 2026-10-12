package com.tarimatwasi.quipu.expense.adapter.out.persistence;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ExpenseJpaRepository extends JpaRepository<ExpenseJpaEntity, Long> {

  List<ExpenseJpaEntity> findByMonthOrderByCreatedDateAscIdAsc(LocalDate month);

  /**
   * Deletes by id with no version check (RN-24): the version guards the edits, and a delete must
   * not fail because someone edited the expense a moment before. Returns the rows deleted.
   */
  @Modifying
  @Query("delete from ExpenseJpaEntity e where e.id = :id")
  int deleteByIdIgnoringVersion(@Param("id") Long id);
}
