package com.tarimatwasi.quipu.expense.adapter.out.persistence;

import com.tarimatwasi.quipu.expense.domain.Expense;
import com.tarimatwasi.quipu.expense.domain.ExpenseCategory;
import com.tarimatwasi.quipu.shared.adapter.out.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** No version column: an expense is edited and deleted freely (RN-24). */
@Entity
@Table(name = "expenses")
public class ExpenseJpaEntity extends AuditableEntity {

  @Id private UUID id;

  @Column(name = "type")
  @Enumerated(EnumType.STRING)
  private ExpenseCategory category;

  private BigDecimal amount;

  /** The first day of the month. */
  private LocalDate month;

  private @Nullable String description;

  protected ExpenseJpaEntity() {}

  ExpenseJpaEntity(
      UUID id,
      ExpenseCategory category,
      BigDecimal amount,
      YearMonth month,
      @Nullable String description) {
    this.id = id;
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
    return new Expense(id, category, amount, YearMonth.from(month), description);
  }
}
