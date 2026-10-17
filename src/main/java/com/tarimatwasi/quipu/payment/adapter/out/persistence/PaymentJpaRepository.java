package com.tarimatwasi.quipu.payment.adapter.out.persistence;

import com.tarimatwasi.quipu.payment.domain.PaymentStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;

interface PaymentJpaRepository
    extends JpaRepository<PaymentJpaEntity, Long>, JpaSpecificationExecutor<PaymentJpaEntity> {

  /** Locked until the commit: two simultaneous resolutions of one payment run one by one. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<PaymentJpaEntity> findWithLockById(Long id);

  Optional<PaymentJpaEntity> findFirstByContractIdAndPeriodAndStatusIn(
      Long contractId, LocalDate period, Collection<PaymentStatus> statuses);

  Optional<PaymentJpaEntity> findFirstByStayIdAndStatusIn(
      Long stayId, Collection<PaymentStatus> statuses);

  List<PaymentJpaEntity> findByContractId(Long contractId);

  List<PaymentJpaEntity> findByStayId(Long stayId);
}
