package com.tarimatwasi.quipu.guest.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

interface StayJpaRepository
    extends JpaRepository<StayJpaEntity, Long>, JpaSpecificationExecutor<StayJpaEntity> {

  List<StayJpaEntity> findByGuestId(Long guestId);

  Optional<StayJpaEntity> findByIdAndGuestId(Long id, Long guestId);
}
