package com.tarimatwasi.quipu.guest.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface GuestDocumentPurgeJpaRepository extends JpaRepository<GuestDocumentPurgeJpaEntity, Long> {

  // createdDate is mapped as an Instant (the field of AbstractAuditable; only its getter is a
  // LocalDateTime), so the instant is bound as it is and the margin does not move with the zone.
  @Query(
      "select p from GuestDocumentPurgeJpaEntity p where p.createdDate < :before"
          + " order by p.id asc")
  List<GuestDocumentPurgeJpaEntity> findAddedBefore(@Param("before") Instant before, Limit limit);

  /** A plain delete: two purges of the same key do not fail on a stale entity. */
  @Modifying
  @Query(value = "DELETE FROM guest_document_purges WHERE storage_key = :key", nativeQuery = true)
  int deleteByKey(@Param("key") String storageKey);
}
