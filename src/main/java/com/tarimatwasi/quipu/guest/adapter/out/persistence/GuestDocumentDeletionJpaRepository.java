package com.tarimatwasi.quipu.guest.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface GuestDocumentDeletionJpaRepository
    extends JpaRepository<GuestDocumentDeletionJpaEntity, Long> {

  Optional<GuestDocumentDeletionJpaEntity> findByDocumentId(Long documentId);

  @Query(
      "select d from GuestDocumentDeletionJpaEntity d where d.createdDate < :before"
          + " order by d.createdDate asc, d.id asc")
  List<GuestDocumentDeletionJpaEntity> findAskedBefore(
      @Param("before") Instant before, Limit limit);

  /** A plain delete: two requests that remove the same one do not fail on a stale entity. */
  @Modifying
  @Query(value = "DELETE FROM guest_document_deletions WHERE document_id = :id", nativeQuery = true)
  int deleteByDocument(@Param("id") Long documentId);
}
