package com.tarimatwasi.quipu.auth.adapter.out.persistence;

import com.tarimatwasi.quipu.auth.domain.DocumentType;
import com.tarimatwasi.quipu.auth.domain.UserAccount;
import com.tarimatwasi.quipu.auth.port.out.UserRepositoryPort;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
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
  public Optional<UserAccount> findById(UUID id) {
    return jpaRepository.findById(id).map(UserJpaEntity::toDomain);
  }

  @Override
  public void changePassword(UUID id, String newPasswordHash) {
    UserJpaEntity user =
        jpaRepository
            .findById(id)
            .orElseThrow(() -> new IllegalStateException("No user with id " + id));
    user.changePassword(newPasswordHash);
    jpaRepository.save(user);
  }

  @Override
  public Optional<UserAccount> findByEmail(String email) {
    return jpaRepository.findByEmailIgnoreCase(email).map(UserJpaEntity::toDomain);
  }

  @Override
  public Optional<Instant> findResetTokenExpiry(UUID id) {
    return jpaRepository.findById(id).flatMap(u -> Optional.ofNullable(u.resetTokenExpiresAt()));
  }

  @Override
  public void saveResetToken(UUID id, String tokenHash, Instant expiresAt) {
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
  public void resetPassword(UUID id, String newPasswordHash) {
    UserJpaEntity user = load(id);
    user.changePassword(newPasswordHash);
    jpaRepository.save(user);
  }

  @Override
  public void registerFailedLogin(UUID id, Instant now, int maxAttempts, Duration lockDuration) {
    UserJpaEntity user =
        jpaRepository
            .findWithLockById(id)
            .orElseThrow(() -> new IllegalStateException("No user with id " + id));
    user.registerFailedLogin(now, maxAttempts, lockDuration);
    jpaRepository.save(user);
  }

  @Override
  public void clearFailedLogins(UUID id) {
    UserJpaEntity user = load(id);
    user.clearFailedLogins();
    jpaRepository.save(user);
  }

  private UserJpaEntity load(UUID id) {
    return jpaRepository
        .findById(id)
        .orElseThrow(() -> new IllegalStateException("No user with id " + id));
  }
}
