package com.tarimatwasi.quipu.environment.adapter.out.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;

interface EnvironmentJpaRepository
    extends JpaRepository<EnvironmentJpaEntity, Long>,
        JpaSpecificationExecutor<EnvironmentJpaEntity> {

  /** Locked until the commit: two simultaneous edits of one environment are applied one by one. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<EnvironmentJpaEntity> findWithLockById(Long id);
}
