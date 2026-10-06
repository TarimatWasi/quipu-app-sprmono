package com.tarimatwasi.quipu.expense.adapter.out.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ExpenseJpaRepository extends JpaRepository<ExpenseJpaEntity, UUID> {

  List<ExpenseJpaEntity> findByMonthOrderByCreatedDateAscIdAsc(LocalDate month);
}
