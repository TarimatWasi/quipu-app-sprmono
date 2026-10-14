package com.tarimatwasi.quipu.payment.adapter.out.persistence;

import com.tarimatwasi.quipu.payment.domain.Payment;
import com.tarimatwasi.quipu.payment.domain.PaymentStatus;
import com.tarimatwasi.quipu.payment.domain.VoucherType;
import com.tarimatwasi.quipu.payment.port.out.PaymentRepositoryPort.DirectDraft;
import com.tarimatwasi.quipu.payment.port.out.PaymentRepositoryPort.StoredPayment;
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
 * A payment is resolved under a version (BE-SPR-DAT-13) and never deleted. The period is stored as
 * the first day of its month.
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

  @Column(name = "payment_method")
  private @Nullable String paymentMethod;

  @Column(name = "voucher_key")
  private String voucherKey;

  @Column(name = "original_name")
  private String originalName;

  @Column(name = "content_type")
  private String contentType;

  @Column(name = "size_bytes")
  private long sizeBytes;

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

  @Version private long version;

  protected PaymentJpaEntity() {}

  /** A payment registered by the administrator, approved at once (RN-23). */
  PaymentJpaEntity(DirectDraft draft) {
    this.guestId = draft.guestId();
    this.contractId = draft.contractId();
    this.stayId = draft.stayId();
    this.period = draft.period() == null ? null : draft.period().atDay(1);
    this.amount = draft.amount();
    this.paymentMethod = draft.paymentMethod();
    this.voucherKey = draft.voucherKey();
    this.originalName = draft.originalName();
    this.contentType = draft.voucherType().contentType();
    this.sizeBytes = draft.sizeBytes();
    this.status = PaymentStatus.APPROVED;
    this.reviewedAt = draft.reviewedAt();
    this.reviewedById = draft.reviewedById();
    this.recordedBy = "ADMIN";
  }

  /** Copies the only things that change after the upload: the resolution. */
  void apply(Payment resolved) {
    this.status = resolved.status();
    this.rejectionReason = resolved.rejectionReason();
    this.reviewedAt = resolved.reviewedAt();
    this.reviewedById = resolved.reviewedById();
  }

  Payment toDomain() {
    return new Payment(
        Objects.requireNonNull(getId()),
        guestId,
        contractId,
        stayId,
        period == null ? null : YearMonth.from(period),
        amount,
        paymentMethod,
        status,
        rejectionReason,
        reviewedAt,
        reviewedById);
  }

  StoredPayment toStored() {
    // The auditing stamps the creation with the clock of the machine; it is read back the same way.
    Instant createdAt = getCreatedDate().orElseThrow().atZone(ZoneId.systemDefault()).toInstant();
    return new StoredPayment(
        toDomain(), voucherKey, VoucherType.ofContentType(contentType), createdAt);
  }
}
