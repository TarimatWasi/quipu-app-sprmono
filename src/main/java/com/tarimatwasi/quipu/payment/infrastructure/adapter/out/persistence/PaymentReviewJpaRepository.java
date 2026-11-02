package com.tarimatwasi.quipu.payment.infrastructure.adapter.out.persistence;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface PaymentReviewJpaRepository extends JpaRepository<PaymentReviewJpaEntity, Long> {

  /** Read by ix_payment_reviews_payment (payment_id, reviewed_at, id). */
  List<PaymentReviewJpaEntity> findByPaymentIdOrderByReviewedAtAscIdAsc(Long paymentId);

  List<PaymentReviewJpaEntity> findByPaymentIdInOrderByReviewedAtAscIdAsc(
      Collection<Long> paymentIds);
}
