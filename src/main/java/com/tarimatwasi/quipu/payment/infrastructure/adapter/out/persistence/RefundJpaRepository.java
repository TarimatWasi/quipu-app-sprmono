package com.tarimatwasi.quipu.payment.infrastructure.adapter.out.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface RefundJpaRepository
    extends JpaRepository<RefundJpaEntity, Long>, JpaSpecificationExecutor<RefundJpaEntity> {

  /** Read by ix_refunds_guest. */
  List<RefundJpaEntity> findByGuestId(Long guestId);

  /** RF-18, RN-39: read by ix_refunds_refunded_on; the sum is null when the month has none. */
  @Query(
      "SELECT SUM(r.amount) AS total, COUNT(r) AS count FROM RefundJpaEntity r"
          + " WHERE r.refundedOn >= :from AND r.refundedOn < :to")
  PaymentJpaRepository.SumAndCount totalBetween(
      @Param("from") LocalDate from, @Param("to") LocalDate to);

  /** Where a page cursor stands, if the refund is one of that month. */
  @Query(
      "SELECT r FROM RefundJpaEntity r WHERE r.id = :id"
          + " AND r.refundedOn >= :from AND r.refundedOn < :to")
  Optional<RefundJpaEntity> findInMonth(
      @Param("id") Long id, @Param("from") LocalDate from, @Param("to") LocalDate to);
}
