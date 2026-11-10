package com.tarimatwasi.quipu.auth.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** TAR-228: refresh tokens are found by the hash of their secret and rotated in one statement. */
interface RefreshTokenJpaRepository extends JpaRepository<RefreshTokenJpaEntity, Long> {

  @Query("SELECT r FROM RefreshTokenJpaEntity r WHERE r.tokenHash = :hash")
  Optional<RefreshTokenJpaEntity> findByTokenHash(@Param("hash") String tokenHash);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          "UPDATE refresh_tokens SET rotated_at = :now, last_modified_date = :now"
              + " WHERE session_id = :sessionId AND rotated_at IS NULL",
      nativeQuery = true)
  int rotateAll(@Param("sessionId") Long sessionId, @Param("now") Instant now);
}
