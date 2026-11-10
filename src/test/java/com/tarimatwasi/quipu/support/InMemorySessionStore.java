package com.tarimatwasi.quipu.support;

import com.tarimatwasi.quipu.auth.application.port.out.SessionStorePort;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/** TAR-228: the sessions and refresh tokens in memory, for the unit tests of their use cases. */
public final class InMemorySessionStore implements SessionStorePort {

  /** A stored session, mutable as the rows are. */
  public static final class Row {
    public final Long id;
    public final Long userId;
    public final String sidHash;
    public long securityVersion;
    public boolean temporary;
    public Instant absoluteExpiresAt;
    public Instant lastUsedAt;
    public @Nullable Instant revokedAt;
    public @Nullable RevokedReason revokedReason;

    Row(
        Long id,
        Long userId,
        String sidHash,
        long securityVersion,
        boolean temporary,
        Instant absoluteExpiresAt,
        Instant lastUsedAt) {
      this.id = id;
      this.userId = userId;
      this.sidHash = sidHash;
      this.securityVersion = securityVersion;
      this.temporary = temporary;
      this.absoluteExpiresAt = absoluteExpiresAt;
      this.lastUsedAt = lastUsedAt;
    }

    StoredSession view() {
      return new StoredSession(
          id,
          userId,
          sidHash,
          securityVersion,
          temporary,
          absoluteExpiresAt,
          lastUsedAt,
          revokedAt);
    }
  }

  /** A stored refresh token. */
  public static final class Token {
    public final Long id;
    public final Long sessionId;
    public final String tokenHash;
    public @Nullable Instant rotatedAt;

    Token(Long id, Long sessionId, String tokenHash) {
      this.id = id;
      this.sessionId = sessionId;
      this.tokenHash = tokenHash;
    }
  }

  private final AtomicLong ids = new AtomicLong();
  private final Map<Long, Row> sessions = new LinkedHashMap<>();
  private final List<Token> tokens = new ArrayList<>();

  public List<Row> sessions() {
    return List.copyOf(sessions.values());
  }

  public List<Token> tokens() {
    return List.copyOf(tokens);
  }

  public Row only() {
    if (sessions.size() != 1) {
      throw new IllegalStateException("Expected one session, found " + sessions.size());
    }
    return sessions.values().iterator().next();
  }

  @Override
  public Long open(
      Long userId,
      String sidHash,
      long securityVersion,
      boolean temporary,
      Instant absoluteExpiresAt,
      Instant now) {
    Long id = ids.incrementAndGet();
    sessions.put(
        id, new Row(id, userId, sidHash, securityVersion, temporary, absoluteExpiresAt, now));
    return id;
  }

  @Override
  public void addRefreshToken(Long sessionId, String tokenHash, Instant now) {
    tokens.add(new Token(ids.incrementAndGet(), sessionId, tokenHash));
  }

  @Override
  public Optional<StoredRefreshToken> findRefreshTokenForUpdate(String tokenHash) {
    return tokens.stream()
        .filter(token -> token.tokenHash.equals(tokenHash))
        .findFirst()
        .map(
            token ->
                new StoredRefreshToken(
                    token.id, token.rotatedAt, sessions.get(token.sessionId).view()));
  }

  @Override
  public Optional<StoredSession> findBySidHashForUpdate(String sidHash) {
    return sessions.values().stream()
        .filter(row -> row.sidHash.equals(sidHash))
        .findFirst()
        .map(Row::view);
  }

  @Override
  public void rotateRefreshTokens(Long sessionId, Instant now) {
    tokens.stream()
        .filter(token -> token.sessionId.equals(sessionId) && token.rotatedAt == null)
        .forEach(token -> token.rotatedAt = now);
  }

  @Override
  public void touch(Long sessionId, Instant now) {
    sessions.get(sessionId).lastUsedAt = now;
  }

  @Override
  public void update(
      Long sessionId, long securityVersion, boolean temporary, Instant absoluteExpiresAt) {
    Row row = sessions.get(sessionId);
    row.securityVersion = securityVersion;
    row.temporary = temporary;
    row.absoluteExpiresAt = absoluteExpiresAt;
  }

  @Override
  public void revoke(Long sessionId, RevokedReason reason, Instant now) {
    Row row = sessions.get(sessionId);
    if (row.revokedAt == null) {
      row.revokedAt = now;
      row.revokedReason = reason;
    }
  }

  @Override
  public void revokeOthers(Long userId, String keptSidHash, RevokedReason reason, Instant now) {
    sessions.values().stream()
        .filter(row -> row.userId.equals(userId) && !row.sidHash.equals(keptSidHash))
        .forEach(row -> revoke(row.id, reason, now));
  }

  @Override
  public boolean isClosed(String sidHash, Instant now) {
    return sessions.values().stream()
        .anyMatch(
            row ->
                row.sidHash.equals(sidHash)
                    && (row.revokedAt != null || !now.isBefore(row.absoluteExpiresAt)));
  }
}
