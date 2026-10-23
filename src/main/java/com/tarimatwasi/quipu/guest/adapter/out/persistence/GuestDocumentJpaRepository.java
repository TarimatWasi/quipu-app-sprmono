package com.tarimatwasi.quipu.guest.adapter.out.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface GuestDocumentJpaRepository extends JpaRepository<GuestDocumentJpaEntity, Long> {

  List<GuestDocumentJpaEntity> findAllByGuestIdOrderByCreatedDateAscIdAsc(Long guestId);

  int countByGuestId(Long guestId);
}
