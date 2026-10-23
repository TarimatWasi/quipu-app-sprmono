package com.tarimatwasi.quipu.guest.adapter.out.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface GuestConsentJpaRepository extends JpaRepository<GuestConsentJpaEntity, Long> {

  Optional<GuestConsentJpaEntity> findFirstByGuestIdOrderByAcceptedAtDescIdDesc(Long guestId);
}
