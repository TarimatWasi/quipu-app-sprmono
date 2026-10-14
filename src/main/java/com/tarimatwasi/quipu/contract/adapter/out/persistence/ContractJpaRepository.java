package com.tarimatwasi.quipu.contract.adapter.out.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

interface ContractJpaRepository
    extends JpaRepository<ContractJpaEntity, Long>, JpaSpecificationExecutor<ContractJpaEntity> {

  Optional<ContractJpaEntity> findFirstByGuestIdOrderByCreatedDateDescIdDesc(Long guestId);
}
