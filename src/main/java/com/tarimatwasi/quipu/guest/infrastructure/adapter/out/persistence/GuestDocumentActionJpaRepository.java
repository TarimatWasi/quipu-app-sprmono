package com.tarimatwasi.quipu.guest.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface GuestDocumentActionJpaRepository
    extends JpaRepository<GuestDocumentActionJpaEntity, Long> {

  List<GuestDocumentActionJpaEntity> findByGuestIdOrderByCreatedDateDescIdDesc(
      Long guestId, Limit limit);

  /** TAR-227: where a page cursor stands, if the action is one of this guest. */
  @Query(
      "select a.createdDate from GuestDocumentActionJpaEntity a"
          + " where a.id = :id and a.guestId = :guestId")
  Optional<Instant> findCreatedDate(@Param("id") Long id, @Param("guestId") Long guestId);

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
