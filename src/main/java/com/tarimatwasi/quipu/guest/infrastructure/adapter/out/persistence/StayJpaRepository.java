package com.tarimatwasi.quipu.guest.infrastructure.adapter.out.persistence;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface StayJpaRepository
    extends JpaRepository<StayJpaEntity, Long>, JpaSpecificationExecutor<StayJpaEntity> {

  List<StayJpaEntity> findByGuestId(Long guestId);

  List<StayJpaEntity> findByGuestIdIn(Collection<Long> guestIds);

  /** TAR-237 (A2): every stay of those environments. */
  List<StayJpaEntity> findByEnvironmentIdIn(Collection<Long> environmentIds);

  /** TAR-237: a stay of the environment, other than the excluded one, crossing the range. */
  @Query(
      "select count(s) > 0 from StayJpaEntity s where s.environmentId = :environmentId"
          + " and s.id <> :excludedStayId and s.startDate <= :end and s.endDate >= :start")
  boolean existsCrossing(
      @Param("environmentId") Long environmentId,
      @Param("start") LocalDate start,
      @Param("end") LocalDate end,
      @Param("excludedStayId") Long excludedStayId);

  /** TAR-237: a stay of the environment that ends on that day or later. */
  @Query(
      "select count(s) > 0 from StayJpaEntity s where s.environmentId = :environmentId"
          + " and s.endDate >= :start")
  boolean existsFrom(@Param("environmentId") Long environmentId, @Param("start") LocalDate start);

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
