package com.tarimatwasi.quipu.ambiente.adapter.out.persistence;

import com.tarimatwasi.quipu.ambiente.domain.EnvironmentStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface EnvironmentJpaRepository extends JpaRepository<EnvironmentJpaEntity, UUID> {

  List<EnvironmentJpaEntity> findByStatusOrderByCodeAsc(EnvironmentStatus status);

  List<EnvironmentJpaEntity> findAllByOrderByCodeAsc();
}
