package com.tarimatwasi.quipu.guest.adapter.out.persistence;

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

  /** RF-18: only the ids, not the rows. */
  @Query(
      "select s.id from StayJpaEntity s where s.startDate between :first and :last order by s.id")
  List<Long> findIdsByStartDateBetween(
      @Param("first") LocalDate first, @Param("last") LocalDate last);
}
