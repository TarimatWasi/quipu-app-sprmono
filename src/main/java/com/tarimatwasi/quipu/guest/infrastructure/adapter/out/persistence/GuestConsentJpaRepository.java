package com.tarimatwasi.quipu.guest.infrastructure.adapter.out.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface GuestConsentJpaRepository extends JpaRepository<GuestConsentJpaEntity, Long> {

  Optional<GuestConsentJpaEntity> findByGuestIdAndVersion(Long guestId, String version);

  Optional<GuestConsentJpaEntity> findFirstByGuestIdOrderByAcceptedAtDescIdDesc(Long guestId);
}
