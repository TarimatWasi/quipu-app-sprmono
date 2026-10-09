package com.tarimatwasi.quipu.auth.adapter.out.persistence;

import com.tarimatwasi.quipu.auth.domain.DocumentType;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

interface UserJpaRepository extends JpaRepository<UserJpaEntity, Long> {
  List<UserJpaEntity> findByGuestIdIn(Collection<Long> guestIds);

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
