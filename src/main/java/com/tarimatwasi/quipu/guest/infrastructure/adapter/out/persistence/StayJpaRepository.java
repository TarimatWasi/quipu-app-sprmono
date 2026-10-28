package com.tarimatwasi.quipu.guest.infrastructure.adapter.out.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface StayJpaRepository
    extends JpaRepository<StayJpaEntity, Long>, JpaSpecificationExecutor<StayJpaEntity> {

  List<StayJpaEntity> findByGuestId(Long guestId);

  Optional<StayJpaEntity> findByIdAndGuestId(Long id, Long guestId);

  /** HU-A04: the stays in course on a day, of every guest. */
  List<StayJpaEntity> findByStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByIdAsc(
      LocalDate onOrBefore, LocalDate onOrAfter);

  /** RF-18: only the ids, not the rows. */
  @Query(
      "select s.id from StayJpaEntity s where s.startDate between :first and :last order by s.id")
  List<Long> findIdsByStartDateBetween(
      @Param("first") LocalDate first, @Param("last") LocalDate last);

  /** TAR-227: where a page cursor stands, if the stay is one of this guest. */
  @Query("select s.startDate from StayJpaEntity s where s.id = :id and s.guestId = :guestId")
  Optional<LocalDate> findStartDate(@Param("id") Long id, @Param("guestId") Long guestId);
}
