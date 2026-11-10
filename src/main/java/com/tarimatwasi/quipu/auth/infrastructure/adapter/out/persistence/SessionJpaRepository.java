package com.tarimatwasi.quipu.auth.infrastructure.adapter.out.persistence;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** TAR-228: the sessions change by conditional updates, decided in the statement itself. */
interface SessionJpaRepository extends JpaRepository<SessionJpaEntity, Long> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT s FROM SessionJpaEntity s WHERE s.id = :id")
  Optional<SessionJpaEntity> findByIdForUpdate(@Param("id") Long id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT s FROM SessionJpaEntity s WHERE s.sidHash = :sidHash")
  Optional<SessionJpaEntity> findBySidHashForUpdate(@Param("sidHash") String sidHash);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value = "UPDATE sessions SET last_used_at = :now, last_modified_date = :now WHERE id = :id",
      nativeQuery = true)
  int touch(@Param("id") Long id, @Param("now") Instant now);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          "UPDATE sessions SET security_version = :version, temporary = :temporary,"
              + " absolute_expires_at = :absolute, last_modified_date = now() WHERE id = :id",
      nativeQuery = true)
  int update(
      @Param("id") Long id,
      @Param("version") long securityVersion,
      @Param("temporary") boolean temporary,
      @Param("absolute") Instant absoluteExpiresAt);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          "UPDATE sessions SET revoked_at = :now, revoked_reason = :reason,"
              + " last_modified_date = :now WHERE id = :id AND revoked_at IS NULL",
      nativeQuery = true)
  int revoke(@Param("id") Long id, @Param("reason") String reason, @Param("now") Instant now);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          "UPDATE sessions SET revoked_at = :now, revoked_reason = :reason,"
              + " last_modified_date = :now"
              + " WHERE user_id = :userId AND sid_hash <> :kept AND revoked_at IS NULL",
      nativeQuery = true)
  int revokeOthers(
      @Param("userId") Long userId,
      @Param("kept") String keptSidHash,
      @Param("reason") String reason,
      @Param("now") Instant now);

  @Query(
      value =
          "SELECT EXISTS (SELECT 1 FROM sessions WHERE sid_hash = :sidHash"
              + " AND (revoked_at IS NOT NULL OR absolute_expires_at <= :now))",
      nativeQuery = true)
  boolean isClosed(@Param("sidHash") String sidHash, @Param("now") Instant now);
}
