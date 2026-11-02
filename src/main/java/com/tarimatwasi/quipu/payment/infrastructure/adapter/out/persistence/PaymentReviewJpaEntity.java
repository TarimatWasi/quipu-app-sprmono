package com.tarimatwasi.quipu.payment.infrastructure.adapter.out.persistence;

import com.tarimatwasi.quipu.payment.domain.PaymentReview;
import com.tarimatwasi.quipu.payment.domain.PaymentStatus;
import com.tarimatwasi.quipu.shared.adapter.out.persistence.AuditorJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.domain.AbstractAuditable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** One decision on a payment (RN-05, TAR-230); it never changes. */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "payment_reviews")
public class PaymentReviewJpaEntity extends AbstractAuditable<AuditorJpaEntity, Long> {

  @Column(name = "payment_id")
  private Long paymentId;

  @Enumerated(EnumType.STRING)
  private PaymentStatus decision;

  private @Nullable String reason;

  @Column(name = "reviewed_at")
  private Instant reviewedAt;

  @Column(name = "reviewed_by_id")
  private Long reviewedById;

  protected PaymentReviewJpaEntity() {}

  PaymentReviewJpaEntity(Long paymentId, PaymentReview review) {
    this.paymentId = paymentId;
    this.decision = review.decision();
    this.reason = review.reason();
    this.reviewedAt = review.at();
    this.reviewedById = review.reviewerId();
  }

  Long paymentId() {
    return paymentId;
  }

  PaymentReview toDomain() {
    return new PaymentReview(decision, reason, reviewedAt, reviewedById);
  }
}
