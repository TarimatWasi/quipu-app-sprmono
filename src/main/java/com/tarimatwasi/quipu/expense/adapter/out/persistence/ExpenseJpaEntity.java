package com.tarimatwasi.quipu.expense.adapter.out.persistence;

import com.tarimatwasi.quipu.expense.domain.Expense;
import com.tarimatwasi.quipu.expense.domain.ExpenseCategory;
import com.tarimatwasi.quipu.shared.adapter.out.persistence.AuditorJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.domain.AbstractAuditable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** No version column: an expense is edited and deleted freely (RN-24). */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "expenses")
public class ExpenseJpaEntity extends AbstractAuditable<AuditorJpaEntity, Long> {

  @Column(name = "type")
  @Enumerated(EnumType.STRING)
  private ExpenseCategory category;

  private BigDecimal amount;

  /** The first day of the month. */
  private LocalDate month;

  private @Nullable String description;

  protected ExpenseJpaEntity() {}

  ExpenseJpaEntity(
      ExpenseCategory category, BigDecimal amount, YearMonth month, @Nullable String description) {
    this.category = category;
    this.amount = amount;
    this.month = month.atDay(1);
    this.description = description;
  }

  void replace(
      ExpenseCategory newCategory,
      BigDecimal newAmount,
      YearMonth newMonth,
      @Nullable String newDescription) {
    this.category = newCategory;
    this.amount = newAmount;
    this.month = newMonth.atDay(1);
    this.description = newDescription;
  }

  Expense toDomain() {
    return new Expense(
        Objects.requireNonNull(getId()), category, amount, YearMonth.from(month), description);
  }
}
