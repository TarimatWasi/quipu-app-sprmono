package com.tarimatwasi.quipu.fine.infrastructure.adapter.out.persistence;

import com.tarimatwasi.quipu.fine.domain.Fine;
import com.tarimatwasi.quipu.shared.adapter.out.persistence.AuditorJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.domain.AbstractAuditable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** Never edited, so it has no version: it is deleted and created again (RN-24). */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "fines")
public class FineJpaEntity extends AbstractAuditable<AuditorJpaEntity, Long> {

  @Column(name = "guest_id")
  private Long guestId;

  private BigDecimal amount;

  /** The first day of the month. */
  private LocalDate month;

  private @Nullable String description;

  protected FineJpaEntity() {}

  FineJpaEntity(Long guestId, BigDecimal amount, YearMonth month, @Nullable String description) {
    this.guestId = guestId;
    this.amount = amount;
    this.month = month.atDay(1);
    this.description = description;
  }

  Fine toDomain() {
    return new Fine(
        Objects.requireNonNull(getId()), guestId, amount, Fine.monthOf(month), description);
  }
}
