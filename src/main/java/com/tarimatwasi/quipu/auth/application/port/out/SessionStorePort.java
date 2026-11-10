package com.tarimatwasi.quipu.auth.application.port.out;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * TAR-228: the sessions and their refresh tokens. The sid and the refresh token arrive here only as
 * their SHA-256: no method takes or returns a secret.
 */
public interface SessionStorePort {

  /** Why a session was closed. */
  enum RevokedReason {
    /** The user logged out. */
    LOGOUT,
    /** A refresh token that was already rotated came back: the session is closed (theft). */
    REUSE,
    /** Another session of the account changed the password. */
    PASSWORD_CHANGE,
    /** The account changed (password, role, deactivation) or the session expired. */
    INVALIDATED
  }

  /** A session as the refresh sees it. */
  record StoredSession(
      Long id,
      Long userId,
      String sidHash,
      long securityVersion,
      boolean temporary,
      Instant absoluteExpiresAt,
      Instant lastUsedAt,
      @Nullable Instant revokedAt) {

    public boolean revoked() {
      return revokedAt != null;
    }
  }

  /** A refresh token with its session, read under the lock of the session. */
  record StoredRefreshToken(Long id, @Nullable Instant rotatedAt, StoredSession session) {}

  /** Opens a session; returns its id. */
  Long open(
      Long userId,
      String sidHash,
      long securityVersion,
      boolean temporary,
      Instant absoluteExpiresAt,
      Instant now);

  /** Stores a new refresh token of the session. */
  void addRefreshToken(Long sessionId, String tokenHash, Instant now);

  /**
   * The refresh token with that hash and its session, the session row locked until the end of the
   * transaction: two refreshes of one session are decided one after the other.
   */
  Optional<StoredRefreshToken> findRefreshTokenForUpdate(String tokenHash);

  /** The session with that sid hash, locked until the end of the transaction. */
  Optional<StoredSession> findBySidHashForUpdate(String sidHash);

  /** Marks every refresh token of the session that is not rotated yet as rotated now. */
  void rotateRefreshTokens(Long sessionId, Instant now);

  /** Records a use of the session (a refresh): the idle expiry counts from here. */
  void touch(Long sessionId, Instant now);

  /**
   * The session leaves its temporary state (its password was chosen, RF-12) or takes the account's
   * new security version, and gets a new absolute expiry.
   */
  void update(Long sessionId, long securityVersion, boolean temporary, Instant absoluteExpiresAt);

  /** Closes the session, if it is open. */
  void revoke(Long sessionId, RevokedReason reason, Instant now);

  /** Closes every open session of the account except the one with that sid hash. */
  void revokeOthers(Long userId, String keptSidHash, RevokedReason reason, Instant now);

  /**
   * Whether the session with that sid hash is closed or past its absolute expiry at {@code now}. A
   * sid that has no session is not closed: tokens without a stored session are told apart by their
   * claims ({@code SessionTokenPort}).
   */
  boolean isClosed(String sidHash, Instant now);

  /** The SHA-256, in hexadecimal, under which a sid or the secret of a refresh token is stored. */
  static String hash(String secret) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("Every Java platform provides SHA-256", e);
    }
  }
}
