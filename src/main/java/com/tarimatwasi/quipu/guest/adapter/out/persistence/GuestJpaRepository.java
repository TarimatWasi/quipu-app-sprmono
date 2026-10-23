package com.tarimatwasi.quipu.guest.adapter.out.persistence;

import com.tarimatwasi.quipu.guest.domain.DocumentType;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;

interface GuestJpaRepository
    extends JpaRepository<GuestJpaEntity, Long>, JpaSpecificationExecutor<GuestJpaEntity> {

  Optional<GuestJpaEntity> findByDocumentTypeAndDocumentNumber(
      DocumentType documentType, String documentNumber);

  /** Locked until the commit: two simultaneous edits of one guest are applied one by one. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<GuestJpaEntity> findWithLockById(Long id);
}
