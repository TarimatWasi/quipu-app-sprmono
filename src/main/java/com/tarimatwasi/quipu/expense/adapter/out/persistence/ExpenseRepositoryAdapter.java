package com.tarimatwasi.quipu.expense.adapter.out.persistence;

import com.tarimatwasi.quipu.expense.domain.Expense;
import com.tarimatwasi.quipu.expense.domain.ExpenseCategory;
import com.tarimatwasi.quipu.expense.port.out.ExpenseRepositoryPort;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
public class ExpenseRepositoryAdapter implements ExpenseRepositoryPort {

  private final ExpenseJpaRepository jpaRepository;

  ExpenseRepositoryAdapter(ExpenseJpaRepository jpaRepository) {
    this.jpaRepository = jpaRepository;
  }

  @Override
  public Expense insert(
      ExpenseCategory category, BigDecimal amount, YearMonth month, @Nullable String description) {
    return jpaRepository
        .save(new ExpenseJpaEntity(category, amount, month, description))
        .toDomain();
  }

  @Override
  public Optional<Expense> update(
      Long id,
      ExpenseCategory category,
      BigDecimal amount,
      YearMonth month,
      @Nullable String description) {
    return jpaRepository
        .findById(id)
        .map(
            entity -> {
              entity.replace(category, amount, month, description);
              return jpaRepository.save(entity).toDomain();
            });
  }

  @Override
  public boolean delete(Long id) {
    if (!jpaRepository.existsById(id)) {
      return false;
    }
    jpaRepository.deleteById(id);
    return true;
  }

  @Override
  public List<Expense> findByMonth(YearMonth month) {
    return jpaRepository.findByMonthOrderByCreatedDateAscIdAsc(month.atDay(1)).stream()
        .map(ExpenseJpaEntity::toDomain)
        .toList();
  }
}
