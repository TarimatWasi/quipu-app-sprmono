package com.tarimatwasi.quipu.auth.adapter.out.persistence;

import com.tarimatwasi.quipu.auth.domain.DocumentType;
import com.tarimatwasi.quipu.auth.domain.UserAccount;
import com.tarimatwasi.quipu.auth.port.out.UserRepositoryPort;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class UserRepositoryAdapter implements UserRepositoryPort {

  private final UserJpaRepository jpaRepository;

  public UserRepositoryAdapter(UserJpaRepository jpaRepository) {
    this.jpaRepository = jpaRepository;
  }

  @Override
  public Optional<UserAccount> findByDocument(DocumentType documentType, String documentNumber) {
    return jpaRepository
        .findByDocumentTypeAndDocumentNumber(documentType, documentNumber)
        .map(UserJpaEntity::toDomain);
  }

  @Override
  public Optional<UserAccount> findByDocumentForUpdate(
      DocumentType documentType, String documentNumber) {
    return jpaRepository
        .findWithLockByDocumentTypeAndDocumentNumber(documentType, documentNumber)
        .map(UserJpaEntity::toDomain);
  }

  @Override
  public Optional<UserAccount> findById(Long id) {
    return jpaRepository.findById(id).map(UserJpaEntity::toDomain);
  }

  @Override
  public Optional<UserAccount> findByIdForUpdate(Long id) {
    return jpaRepository.findWithLockById(id).map(UserJpaEntity::toDomain);
  }

  @Override
  public void changePassword(Long id, String newPasswordHash, Instant changedAt) {
    UserJpaEntity user =
        jpaRepository
            .findById(id)
            .orElseThrow(() -> new IllegalStateException("No user with id " + id));
    user.changePassword(newPasswordHash, changedAt);
    jpaRepository.save(user);
  }

  @Override
  public List<UserAccount> findAllByGuestIds(Collection<Long> guestIds) {
    return jpaRepository.findByGuestIdIn(guestIds).stream().map(UserJpaEntity::toDomain).toList();
  }

  @Override
  public Optional<UserAccount> findByEmail(String email) {
    return jpaRepository.findByEmailIgnoreCase(email).map(UserJpaEntity::toDomain);
  }

  @Override
  public Optional<Instant> findResetTokenExpiry(Long id) {
    return jpaRepository.findById(id).flatMap(u -> Optional.ofNullable(u.resetTokenExpiresAt()));
  }

  @Override
  public void saveResetToken(Long id, String tokenHash, Instant expiresAt) {
    UserJpaEntity user = load(id);
    user.replaceResetToken(tokenHash, expiresAt);
    jpaRepository.save(user);
  }

  @Override
  public Optional<PendingReset> findPendingReset(String tokenHash) {
    return jpaRepository
        .findByResetTokenHash(tokenHash)
        .map(
            user ->
                new PendingReset(
                    user.toDomain(), Objects.requireNonNull(user.resetTokenExpiresAt())));
  }

  @Override
  public void resetPassword(Long id, String newPasswordHash, Instant changedAt) {
    UserJpaEntity user = load(id);
    user.changePassword(newPasswordHash, changedAt);
    jpaRepository.save(user);
  }

  @Override
  public boolean registerFailedLogin(Long id, Instant now, int maxAttempts, Duration lockDuration) {
    UserJpaEntity user = loadLocked(id);
    boolean alreadyLocked = user.registerFailedLogin(now, maxAttempts, lockDuration);
    jpaRepository.save(user);
    return alreadyLocked;
  }

  @Override
  public void clearFailedLogins(Long id) {
    UserJpaEntity user = loadLocked(id);
    user.clearFailedLogins();
    jpaRepository.save(user);
  }

  /** The row stays locked until the commit, so concurrent updates of the counter queue up. */
  private UserJpaEntity loadLocked(Long id) {
    return jpaRepository
        .findWithLockById(id)
        .orElseThrow(() -> new IllegalStateException("No user with id " + id));
  }

  private UserJpaEntity load(Long id) {
    return jpaRepository
        .findById(id)
        .orElseThrow(() -> new IllegalStateException("No user with id " + id));
  }
}
