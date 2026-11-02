package com.tarimatwasi.quipu.payment.infrastructure.adapter.out.persistence;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface PaymentProofJpaRepository extends JpaRepository<PaymentProofJpaEntity, Long> {

  /** Read by ix_payment_proofs_payment (payment_id, id). */
  List<PaymentProofJpaEntity> findByPaymentIdInOrderByIdAsc(Collection<Long> paymentIds);

  List<PaymentProofJpaEntity> findByPaymentIdAndIdIn(Long paymentId, Collection<Long> ids);
}
