package com.tarimatwasi.quipu.guest.infrastructure.adapter.out.persistence;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface GuestDocumentJpaRepository extends JpaRepository<GuestDocumentJpaEntity, Long> {

  /** Without the documents whose deletion is in course: their object may already be gone. */
  @Query(
      "select g from GuestDocumentJpaEntity g where g.guestId = :guestId and not exists"
          + " (select 1 from GuestDocumentDeletionJpaEntity d where d.documentId = g.id)"
          + " order by g.createdDate asc, g.id asc")
  List<GuestDocumentJpaEntity> findListedByGuestId(@Param("guestId") Long guestId);

  /** As {@link #findListedByGuestId}: a document being deleted no longer counts. */
  @Query(
      "select count(g) from GuestDocumentJpaEntity g where g.guestId = :guestId and not exists"
          + " (select 1 from GuestDocumentDeletionJpaEntity d where d.documentId = g.id)")
  int countListedByGuestId(@Param("guestId") Long guestId);

  boolean existsByStorageKey(String storageKey);

  Optional<GuestDocumentJpaEntity> findByIdAndGuestId(Long id, Long guestId);

  /** The document of that guest, locked until the commit (RN-37). */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<GuestDocumentJpaEntity> findWithLockByIdAndGuestId(Long id, Long guestId);

  /** Removes the row; the database refuses it unless its evidence is already written (trigger). */
  @Modifying(flushAutomatically = true)
  @Query(
      value = "DELETE FROM guest_documents WHERE id = :id AND guest_id = :guestId",
      nativeQuery = true)
  int deleteByIdAndGuest(@Param("id") Long id, @Param("guestId") Long guestId);
}
