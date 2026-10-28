package com.tarimatwasi.quipu.contract.infrastructure.adapter.out.persistence;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ContractJpaRepository
    extends JpaRepository<ContractJpaEntity, Long>, JpaSpecificationExecutor<ContractJpaEntity> {

  /** Locked until the commit: two simultaneous terminations of one contract run one by one. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<ContractJpaEntity> findWithLockById(Long id);

  List<ContractJpaEntity> findByGuestIdOrderByStartDateAscIdAsc(Long guestId);

  /** Every contract of the guest, locked until the commit in the order of their ids. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  List<ContractJpaEntity> findWithLockByGuestIdOrderByIdAsc(Long guestId);

  Optional<ContractJpaEntity> findFirstByGuestIdOrderByCreatedDateDescIdDesc(Long guestId);

  /**
   * TAR-227: where a page cursor stands. createdDate is mapped as an Instant (the field of
   * AbstractAuditable; only its getter is a LocalDateTime).
   */
  @Query("select c.createdDate from ContractJpaEntity c where c.id = :id")
  Optional<Instant> findCreatedDate(@Param("id") Long id);
}
