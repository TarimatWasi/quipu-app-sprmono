package com.tarimatwasi.quipu.payment.infrastructure.adapter.out.persistence;

import com.tarimatwasi.quipu.payment.application.port.out.PaymentRepositoryPort.NewPayment;
import com.tarimatwasi.quipu.payment.domain.Payment;
import com.tarimatwasi.quipu.payment.domain.PaymentStatus;
import com.tarimatwasi.quipu.shared.adapter.out.persistence.AuditorJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.domain.AbstractAuditable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * A payment is resolved and corrected under a version (BE-SPR-DAT-13) and never deleted. The period
 * is stored as the first day of its month; its proofs are rows of payment_proofs.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "payments")
public class PaymentJpaEntity extends AbstractAuditable<AuditorJpaEntity, Long> {

  @Column(name = "guest_id")
  private Long guestId;

  @Column(name = "contract_id")
  private @Nullable Long contractId;

  @Column(name = "stay_id")
  private @Nullable Long stayId;

  private @Nullable LocalDate period;

  private BigDecimal amount;

  @Column(name = "verified_amount")
  private @Nullable BigDecimal verifiedAmount;

  @Enumerated(EnumType.STRING)
  private PaymentStatus status;

  @Column(name = "rejection_reason")
  private @Nullable String rejectionReason;

  @Column(name = "reviewed_at")
  private @Nullable Instant reviewedAt;

  @Column(name = "reviewed_by_id")
  private @Nullable Long reviewedById;

  @Column(name = "recorded_by")
  private String recordedBy;

  @Column(name = "submitted_at")
  private Instant submittedAt;

  @Version private long version;

  protected PaymentJpaEntity() {}

  /** A new payment: the declared amount is the sum of its proofs. */
  PaymentJpaEntity(NewPayment draft, BigDecimal declared, @Nullable BigDecimal verified) {
    this.guestId = draft.guestId();
    this.contractId = draft.contractId();
    this.stayId = draft.stayId();
    this.period = draft.period() == null ? null : draft.period().atDay(1);
    this.amount = declared;
    this.verifiedAmount = verified;
    this.status = draft.status();
    this.reviewedAt = draft.reviewedAt();
    this.reviewedById = draft.reviewedById();
    this.recordedBy = draft.recordedBy().name();
    this.submittedAt = draft.submittedAt();
  }

  /** Copies what changes after the payment was sent: its amounts, its review and its last send. */
  void apply(Payment changed) {
    this.amount = changed.amount();
    this.verifiedAmount = changed.verifiedAmount();
    this.status = changed.status();
    this.rejectionReason = changed.rejectionReason();
    this.reviewedAt = changed.reviewedAt();
    this.reviewedById = changed.reviewedById();
    this.submittedAt = changed.submittedAt();
  }

  Payment toDomain() {
    return new Payment(
        Objects.requireNonNull(getId()),
        guestId,
        contractId,
        stayId,
        period == null ? null : YearMonth.from(period),
        amount,
        verifiedAmount,
        status,
        rejectionReason,
        reviewedAt,
        reviewedById,
        submittedAt);
  }

  /**
   * The auditing stamps the creation with the clock of the machine; it is read back the same way.
   */
  Instant createdAt() {
    return getCreatedDate().orElseThrow().atZone(ZoneId.systemDefault()).toInstant();
  }
}
