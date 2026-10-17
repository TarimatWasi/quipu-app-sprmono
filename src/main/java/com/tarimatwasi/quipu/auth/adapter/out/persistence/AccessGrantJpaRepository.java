package com.tarimatwasi.quipu.auth.adapter.out.persistence;

import com.tarimatwasi.quipu.shared.access.AccessMode;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The accesses are used and revoked by conditional updates: the condition is checked and the row
 * changed in one statement, so two simultaneous requests cannot both use one access.
 */
interface AccessGrantJpaRepository extends JpaRepository<AccessGrantJpaEntity, Long> {

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          "UPDATE access_grants SET revoked_at = :now, last_modified_date = :now"
              + " WHERE guest_id = :guestId AND used_at IS NULL AND revoked_at IS NULL",
      nativeQuery = true)
  int revokeLive(@Param("guestId") Long guestId, @Param("now") Instant now);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          "UPDATE access_grants SET used_at = :now, last_modified_date = :now"
              + " WHERE token_hash = :hash AND mode = 'LINK' AND used_at IS NULL"
              + " AND revoked_at IS NULL AND expires_at > :now",
      nativeQuery = true)
  int consumeLink(@Param("hash") String tokenHash, @Param("now") Instant now);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          "UPDATE access_grants SET used_at = :now, last_modified_date = :now"
              + " WHERE guest_id = :guestId AND mode = 'TEMPORARY_PASSWORD' AND used_at IS NULL"
              + " AND revoked_at IS NULL",
      nativeQuery = true)
  int consumeTemporaryPassword(@Param("guestId") Long guestId, @Param("now") Instant now);

  @Query(
      value =
          "SELECT count(*) > 0 FROM access_grants WHERE id = :id AND guest_id = :guestId"
              + " AND mode = 'TEMPORARY_PASSWORD' AND used_at IS NULL AND revoked_at IS NULL"
              + " AND expires_at > :now",
      nativeQuery = true)
  boolean isLiveTemporaryPassword(
      @Param("id") Long grantId, @Param("guestId") Long guestId, @Param("now") Instant now);

  @Query(
      value =
          "SELECT guest_id FROM access_grants WHERE guest_id IN (:guestIds)"
              + " AND mode = 'TEMPORARY_PASSWORD' AND used_at IS NULL AND revoked_at IS NULL"
              + " AND expires_at > :now",
      nativeQuery = true)
  List<Long> findGuestsWithLiveTemporaryPassword(
      @Param("guestIds") Collection<Long> guestIds, @Param("now") Instant now);

  @Query(value = "SELECT guest_id FROM access_grants WHERE token_hash = :hash", nativeQuery = true)
  Optional<Long> findGuestIdByTokenHash(@Param("hash") String tokenHash);

  Optional<AccessGrantJpaEntity> findByGuestIdAndModeAndUsedAtIsNullAndRevokedAtIsNull(
      Long guestId, AccessMode mode);

  Optional<AccessGrantJpaEntity> findByGuestIdAndUsedAtIsNullAndRevokedAtIsNullAndExpiresAtAfter(
      Long guestId, Instant now);
}
