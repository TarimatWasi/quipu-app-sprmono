package com.tarimatwasi.quipu.expense.adapter.out.persistence;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface ExpenseJpaRepository extends JpaRepository<ExpenseJpaEntity, Long> {

  List<ExpenseJpaEntity> findByMonthOrderByCreatedDateAscIdAsc(LocalDate month);
}
