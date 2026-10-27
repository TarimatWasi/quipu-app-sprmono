package com.tarimatwasi.quipu.fine.adapter.out.persistence;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface FineJpaRepository extends JpaRepository<FineJpaEntity, Long> {

  /**
   * A plain delete by id (RN-24): two requests that delete the same fine do not fail on a stale
   * entity, and the one that comes second finds nothing to delete. Returns the rows deleted.
   */
  @Modifying
  @Query("delete from FineJpaEntity f where f.id = :id")
  int deleteFine(@Param("id") Long id);

  List<FineJpaEntity> findByMonthOrderByCreatedDateAscIdAsc(LocalDate month);
}
