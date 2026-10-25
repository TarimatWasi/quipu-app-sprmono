package com.tarimatwasi.quipu.guest.adapter.out.persistence;

import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface GuestDocumentPurgeJpaRepository extends JpaRepository<GuestDocumentPurgeJpaEntity, Long> {

  List<GuestDocumentPurgeJpaEntity> findAllByOrderByIdAsc(Limit limit);

  /** A plain delete: two purges of the same key do not fail on a stale entity. */
  @Modifying
  @Query(value = "DELETE FROM guest_document_purges WHERE storage_key = :key", nativeQuery = true)
  int deleteByKey(@Param("key") String storageKey);
}
