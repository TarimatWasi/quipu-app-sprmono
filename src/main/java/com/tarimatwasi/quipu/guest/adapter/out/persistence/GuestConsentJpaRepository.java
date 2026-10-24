package com.tarimatwasi.quipu.guest.adapter.out.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface GuestConsentJpaRepository extends JpaRepository<GuestConsentJpaEntity, Long> {

  boolean existsByGuestIdAndVersion(Long guestId, String version);

  Optional<GuestConsentJpaEntity> findFirstByGuestIdOrderByAcceptedAtDescIdDesc(Long guestId);
}
