package com.tarimatwasi.quipu.auth.infrastructure.adapter.out.persistence;

import com.tarimatwasi.quipu.auth.application.port.out.SessionStorePort;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * TAR-228: the sessions and refresh tokens in PostgreSQL. A refresh token is found first and its
 * session then locked, so two refreshes of one session are decided one after the other; the token
 * is read again under that lock, since the first reading may predate a rotation. Every path locks
 * the session before it touches its tokens, so a refresh and a password change of one session never
 * wait on each other in opposite orders.
 */
@Component
class SessionStoreAdapter implements SessionStorePort {

  private final SessionJpaRepository sessions;
  private final RefreshTokenJpaRepository refreshTokens;
  private final EntityManager entityManager;

  SessionStoreAdapter(
      SessionJpaRepository sessions,
      RefreshTokenJpaRepository refreshTokens,
      EntityManager entityManager) {
    this.sessions = sessions;
    this.refreshTokens = refreshTokens;
    this.entityManager = entityManager;
  }

  @Override
  public Long open(
      Long userId,
      String sidHash,
      long securityVersion,
      boolean temporary,
      Instant absoluteExpiresAt,
      Instant now) {
    return sessions
        .saveAndFlush(
            new SessionJpaEntity(
                userId, sidHash, securityVersion, temporary, absoluteExpiresAt, now))
        .getId();
  }

  @Override
  public void addRefreshToken(Long sessionId, String tokenHash, Instant now) {
    refreshTokens.saveAndFlush(new RefreshTokenJpaEntity(sessionId, tokenHash, now));
  }

  @Override
  public Optional<StoredRefreshToken> findRefreshTokenForUpdate(String tokenHash) {
    Optional<RefreshTokenJpaEntity> found = refreshTokens.findByTokenHash(tokenHash);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    Optional<StoredSession> session =
        sessions.findByIdForUpdate(found.get().sessionId()).map(SessionStoreAdapter::view);
    if (session.isEmpty()) {
      return Optional.empty();
    }
    // Under the lock of the session, read from the database and not from the persistence context:
    // a rotation committed meanwhile shows now.
    entityManager.clear();
    return refreshTokens
        .findByTokenHash(tokenHash)
        .map(token -> new StoredRefreshToken(token.getId(), token.rotatedAt(), session.get()));
  }

  @Override
  public Optional<StoredSession> findBySidHashForUpdate(String sidHash) {
    return sessions.findBySidHashForUpdate(sidHash).map(SessionStoreAdapter::view);
  }

  @Override
  public void rotateRefreshTokens(Long sessionId, Instant now) {
    refreshTokens.rotateAll(sessionId, now);
  }

  @Override
  public void touch(Long sessionId, Instant now) {
    sessions.touch(sessionId, now);
  }

  @Override
  public void update(
      Long sessionId, long securityVersion, boolean temporary, Instant absoluteExpiresAt) {
    sessions.update(sessionId, securityVersion, temporary, absoluteExpiresAt);
  }

  @Override
  public void revoke(Long sessionId, RevokedReason reason, Instant now) {
    sessions.revoke(sessionId, reason.name(), now);
  }

  @Override
  public void revokeOthers(Long userId, String keptSidHash, RevokedReason reason, Instant now) {
    sessions.revokeOthers(userId, keptSidHash, reason.name(), now);
  }

  @Override
  public boolean isClosed(String sidHash, Instant now) {
    return sessions.isClosed(sidHash, now);
  }

  private static StoredSession view(SessionJpaEntity session) {
    return new StoredSession(
        session.getId(),
        session.userId(),
        session.sidHash(),
        session.securityVersion(),
        session.temporary(),
        session.absoluteExpiresAt(),
        session.lastUsedAt(),
        session.revokedAt());
  }
}
