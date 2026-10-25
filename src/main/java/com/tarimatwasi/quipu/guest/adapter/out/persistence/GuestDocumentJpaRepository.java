package com.tarimatwasi.quipu.guest.adapter.out.persistence;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface GuestDocumentJpaRepository extends JpaRepository<GuestDocumentJpaEntity, Long> {

  List<GuestDocumentJpaEntity> findAllByGuestIdOrderByCreatedDateAscIdAsc(Long guestId);

  int countByGuestId(Long guestId);

  /** The document of that guest, locked until the commit (RN-37). */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<GuestDocumentJpaEntity> findWithLockByIdAndGuestId(Long id, Long guestId);

  /** Removes the row; the database refuses it unless its evidence is already written (V18). */
  @Modifying(flushAutomatically = true)
  @Query(
      value = "DELETE FROM guest_documents WHERE id = :id AND guest_id = :guestId",
      nativeQuery = true)
  int deleteByIdAndGuest(@Param("id") Long id, @Param("guestId") Long guestId);
}
