package com.tarimatwasi.quipu.payment.infrastructure.adapter.out.persistence;

import com.tarimatwasi.quipu.payment.domain.Refund;
import com.tarimatwasi.quipu.shared.adapter.out.persistence.AuditorJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.domain.AbstractAuditable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** A refund to a guest (RN-39); it never changes. */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "refunds")
public class RefundJpaEntity extends AbstractAuditable<AuditorJpaEntity, Long> {

  @Column(name = "guest_id")
  private Long guestId;

  @Column(name = "from_payment_id")
  private Long fromPaymentId;

  private BigDecimal amount;

  @Column(name = "refunded_on")
  private LocalDate refundedOn;

  private String method;

  private @Nullable String note;

  protected RefundJpaEntity() {}

  RefundJpaEntity(
      Long guestId,
      Long fromPaymentId,
      BigDecimal amount,
      LocalDate refundedOn,
      String method,
      @Nullable String note) {
    this.guestId = guestId;
    this.fromPaymentId = fromPaymentId;
    this.amount = amount;
    this.refundedOn = refundedOn;
    this.method = method;
    this.note = note;
  }

  Refund toDomain() {
    return new Refund(
        Objects.requireNonNull(getId()),
        guestId,
        fromPaymentId,
        amount,
        refundedOn,
        method,
        note,
        // As the payments read it: the getter of AbstractAuditable is a LocalDateTime
        getCreatedDate().orElseThrow().atZone(ZoneId.systemDefault()).toInstant());
  }
}
