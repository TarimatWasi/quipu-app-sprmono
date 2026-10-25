package com.tarimatwasi.quipu.guest.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

interface GuestDocumentPurgeJpaRepository extends JpaRepository<GuestDocumentPurgeJpaEntity, Long> {

  List<GuestDocumentPurgeJpaEntity> findAllByOrderByIdAsc(Limit limit);

  Optional<GuestDocumentPurgeJpaEntity> findByStorageKey(String storageKey);
}
