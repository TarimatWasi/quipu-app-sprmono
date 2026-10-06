package com.tarimatwasi.quipu.environment.adapter.out.persistence;

import com.tarimatwasi.quipu.environment.domain.EnvironmentStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

interface EnvironmentJpaRepository extends JpaRepository<EnvironmentJpaEntity, UUID> {

  /** Locked until the commit: two simultaneous edits of one environment are applied one by one. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<EnvironmentJpaEntity> findWithLockById(UUID id);

  List<EnvironmentJpaEntity> findByStatusOrderByCodeAsc(EnvironmentStatus status);

  List<EnvironmentJpaEntity> findAllByOrderByCodeAsc();
}
