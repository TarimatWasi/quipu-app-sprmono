package com.tarimatwasi.quipu.guest.adapter.out.persistence;

import com.tarimatwasi.quipu.guest.domain.GuestStatus;
import com.tarimatwasi.quipu.guest.domain.GuestType;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

interface GuestJpaRepository extends JpaRepository<GuestJpaEntity, UUID> {

  /** Locked until the commit: two simultaneous edits of one guest are applied one by one. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<GuestJpaEntity> findWithLockById(UUID id);

  List<GuestJpaEntity> findAllByOrderByDocumentTypeAscDocumentNumberAsc();

  List<GuestJpaEntity> findByStatusInOrderByDocumentTypeAscDocumentNumberAsc(
      Collection<GuestStatus> statuses);

  List<GuestJpaEntity> findByTypeOrderByDocumentTypeAscDocumentNumberAsc(GuestType type);

  List<GuestJpaEntity> findByStatusInAndTypeOrderByDocumentTypeAscDocumentNumberAsc(
      Collection<GuestStatus> statuses, GuestType type);
}
