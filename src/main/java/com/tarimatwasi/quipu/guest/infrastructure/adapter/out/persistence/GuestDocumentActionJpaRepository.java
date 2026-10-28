package com.tarimatwasi.quipu.guest.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface GuestDocumentActionJpaRepository
    extends JpaRepository<GuestDocumentActionJpaEntity, Long> {

  List<GuestDocumentActionJpaEntity> findByGuestIdOrderByCreatedDateDescIdDesc(
      Long guestId, Limit limit);

  // createdDate is mapped as an Instant (the field of AbstractAuditable; only its getter is a
  // LocalDateTime), so the instant is bound as it is and the margin does not move with the zone.
  /** The page after (date, id), in the order of the listing: newest first. */
  @Query(
      "select a from GuestDocumentActionJpaEntity a where a.guestId = :guestId"
          + " and (a.createdDate < :date or (a.createdDate = :date and a.id < :id))"
          + " order by a.createdDate desc, a.id desc")
  List<GuestDocumentActionJpaEntity> findPageAfter(
      @Param("guestId") Long guestId,
      @Param("date") Instant date,
      @Param("id") Long id,
      Limit limit);
}
