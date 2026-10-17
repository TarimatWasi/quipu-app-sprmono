package com.tarimatwasi.quipu.auth.adapter.out.persistence;

import com.tarimatwasi.quipu.auth.domain.DocumentType;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface UserJpaRepository extends JpaRepository<UserJpaEntity, Long> {
  List<UserJpaEntity> findByGuestIdIn(Collection<Long> guestIds);

  /**
   * Writes only the status (and the audit date) of the guest's account: the row is never rewritten
   * whole, so a login, a password change or a reset that commits meanwhile keeps its columns. The
   * persistence context is flushed before and cleared after, so no stale copy is written later.
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          "UPDATE users SET status = :status, last_modified_date = now() WHERE guest_id = :guestId",
      nativeQuery = true)
  int updateStatusByGuestId(@Param("guestId") Long guestId, @Param("status") String status);

  Optional<UserJpaEntity> findByDocumentTypeAndDocumentNumber(
      DocumentType documentType, String documentNumber);

  /** Locked until the commit: simultaneous logins of one account are decided one by one. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<UserJpaEntity> findWithLockByDocumentTypeAndDocumentNumber(
      DocumentType documentType, String documentNumber);

  /** Locked until the commit: simultaneous failed logins of one account count one by one. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<UserJpaEntity> findWithLockById(Long id);

  /** Locked until the commit: two simultaneous recovery requests for one account queue up. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<UserJpaEntity> findByEmailIgnoreCase(String email);

  /** Locked until the commit: of two simultaneous resets with one code only one finds it. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<UserJpaEntity> findByResetTokenHash(String resetTokenHash);
}
