package com.tarimatwasi.quipu.auth.application.service;

import com.tarimatwasi.quipu.auth.application.port.in.IssuedSession;
import com.tarimatwasi.quipu.auth.application.port.in.LogoutUseCase;
import com.tarimatwasi.quipu.auth.application.port.in.NoActiveSessionException;
import com.tarimatwasi.quipu.auth.application.port.in.RefreshSessionUseCase;
import com.tarimatwasi.quipu.auth.application.port.out.SessionStorePort;
import com.tarimatwasi.quipu.auth.application.port.out.SessionStorePort.RevokedReason;
import com.tarimatwasi.quipu.auth.application.port.out.SessionStorePort.StoredRefreshToken;
import com.tarimatwasi.quipu.auth.application.port.out.SessionStorePort.StoredSession;
import com.tarimatwasi.quipu.auth.application.port.out.SessionTokenPort;
import com.tarimatwasi.quipu.auth.application.port.out.UserRepositoryPort;
import com.tarimatwasi.quipu.auth.domain.UserAccount;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TAR-228: the sessions of the accounts and their refresh tokens.
 *
 * <p>A session opens with a login or an activation and is named by its sid (TAR-227), which the
 * access token carries. Its refresh token is {@code <sid>.<secret>}: the secret is 256 random bits
 * and only its SHA-256 is stored; the sid binds the token to its session. Each refresh rotates the
 * token. One that comes back after its rotation is a reuse (the token was copied): the session is
 * closed, except within {@link SessionPolicy#reuseGrace()} of the rotation, when tabs that share
 * the cookies raced and the browser already holds the new ones.
 *
 * <p>The changes of the account (password, role, deactivation) raise its security version, which
 * already refuses its access tokens; a refresh refuses them too, since the session keeps the
 * version it was issued with.
 */
@Service
public class SessionService implements RefreshSessionUseCase, LogoutUseCase {

  private static final Logger LOGGER = LoggerFactory.getLogger(SessionService.class);

  /** Thread-safe; one for the whole process. */
  private static final SecureRandom RANDOM = new SecureRandom();

  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

  /** 256 bits, as the recovery codes (SEG-05). */
  private static final int SECRET_BYTES = 32;

  /** A sid of 128 bits and a secret of 256, both Base64Url without padding. */
  private static final Pattern REFRESH_TOKEN =
      Pattern.compile("([A-Za-z0-9_-]{22})\\.([A-Za-z0-9_-]{43})");

  private final SessionStorePort sessions;
  private final SessionTokenPort tokens;
  private final UserRepositoryPort users;
  private final SessionPolicy policy;
  private final Clock clock;

  public SessionService(
      SessionStorePort sessions,
      SessionTokenPort tokens,
      UserRepositoryPort users,
      SessionPolicy policy,
      Clock clock) {
    this.sessions = sessions;
    this.tokens = tokens;
    this.users = users;
    this.policy = policy;
    this.clock = clock;
  }

  /** Opens a session of the account (login, activation). Joins the caller's transaction. */
  IssuedSession open(String userId) {
    Instant now = clock.instant();
    String sid = randomToken(IdMasker.SESSION_ID_BYTES);
    Instant absolute = now.plus(policy.absolute());
    Long id =
        sessions.open(
            Long.parseLong(userId),
            SessionStorePort.hash(sid),
            versionOf(userId),
            false,
            absolute,
            now);
    return issue(userId, sid, id, now, absolute);
  }

  /**
   * RF-12: a session opened with a temporary password. It has no refresh token and ends with the
   * password; it gets one when it chooses its own ({@link #continueAfterPasswordChange}).
   */
  IssuedSession openTemporary(String userId, Long grantId, Instant expiresAt) {
    Instant now = clock.instant();
    String sid = randomToken(IdMasker.SESSION_ID_BYTES);
    sessions.open(
        Long.parseLong(userId),
        SessionStorePort.hash(sid),
        versionOf(userId),
        true,
        expiresAt,
        now);
    return new IssuedSession(tokens.issueTemporary(userId, sid, grantId, expiresAt), null, null);
  }

  /**
   * After a password change the same session goes on (TAR-227) with the new security version and a
   * fresh refresh token; every other session of the account is closed. A temporary session becomes
   * a full one, with its 30 days from now. Joins the caller's transaction.
   */
  IssuedSession continueAfterPasswordChange(String userId, String sessionId) {
    Instant now = clock.instant();
    String sidHash = SessionStorePort.hash(sessionId);
    long version = versionOf(userId);
    StoredSession session =
        sessions
            .findBySidHashForUpdate(sidHash)
            .orElseGet(() -> adopt(userId, sidHash, version, now));
    Instant absolute =
        session.temporary() ? now.plus(policy.absolute()) : session.absoluteExpiresAt();
    sessions.update(session.id(), version, false, absolute);
    sessions.revokeOthers(Long.parseLong(userId), sidHash, RevokedReason.PASSWORD_CHANGE, now);
    sessions.rotateRefreshTokens(session.id(), now);
    sessions.touch(session.id(), now);
    return issue(userId, sessionId, session.id(), now, absolute);
  }

  /** The revocations stay even though the refresh ends in an exception. */
  @Override
  @Transactional(noRollbackFor = NoActiveSessionException.class)
  public Optional<IssuedSession> refresh(String refreshToken) {
    Parts parts = parse(refreshToken).orElseThrow(NoActiveSessionException::new);
    StoredRefreshToken stored =
        sessions
            .findRefreshTokenForUpdate(SessionStorePort.hash(parts.secret()))
            .orElseThrow(NoActiveSessionException::new);
    StoredSession session = stored.session();
    if (!session.sidHash().equals(SessionStorePort.hash(parts.sid())) || session.revoked()) {
      throw new NoActiveSessionException();
    }
    Instant now = clock.instant();
    Instant rotatedAt = stored.rotatedAt();
    if (rotatedAt != null) {
      if (now.isBefore(rotatedAt.plus(policy.reuseGrace()))) {
        return Optional.empty();
      }
      sessions.revoke(session.id(), RevokedReason.REUSE, now);
      // The session id is ours and internal: neither the token nor the account reaches the log.
      LOGGER.warn("A rotated refresh token was used again: session {} closed", session.id());
      throw new NoActiveSessionException();
    }
    if (!isUsable(session, now)) {
      sessions.revoke(session.id(), RevokedReason.INVALIDATED, now);
      throw new NoActiveSessionException();
    }
    sessions.rotateRefreshTokens(session.id(), now);
    sessions.touch(session.id(), now);
    return Optional.of(
        issue(
            session.userId().toString(),
            parts.sid(),
            session.id(),
            now,
            session.absoluteExpiresAt()));
  }

  /**
   * The refresh token names the session if it is ours, rotated or not (closing is always safe);
   * else the access token does. An access token whose session was never stored (issued before
   * TAR-228 by the tests' own signer) has nothing to close on the server and just ends.
   */
  @Override
  @Transactional
  public void logout(@Nullable String refreshToken, @Nullable String accessToken) {
    Instant now = clock.instant();
    Optional<StoredSession> byRefresh =
        Optional.ofNullable(refreshToken).flatMap(this::parse).flatMap(this::sessionOf);
    if (byRefresh.isPresent()) {
      sessions.revoke(byRefresh.get().id(), RevokedReason.LOGOUT, now);
      return;
    }
    String sid =
        Optional.ofNullable(accessToken)
            .flatMap(tokens::sessionIdOf)
            .orElseThrow(NoActiveSessionException::new);
    sessions
        .findBySidHashForUpdate(SessionStorePort.hash(sid))
        .ifPresent(session -> sessions.revoke(session.id(), RevokedReason.LOGOUT, now));
  }

  private Optional<StoredSession> sessionOf(Parts parts) {
    return sessions
        .findRefreshTokenForUpdate(SessionStorePort.hash(parts.secret()))
        .map(StoredRefreshToken::session)
        .filter(session -> session.sidHash().equals(SessionStorePort.hash(parts.sid())));
  }

  /**
   * Not temporary (it has no refresh token of its own), within its 30 days and its idle week, and
   * its account still the one it was issued for: enabled and with the same security version.
   */
  private boolean isUsable(StoredSession session, Instant now) {
    if (session.temporary()
        || !now.isBefore(session.absoluteExpiresAt())
        || !now.isBefore(session.lastUsedAt().plus(policy.idle()))) {
      return false;
    }
    UserAccount account = users.findById(session.userId()).orElse(null);
    return account != null
        && !account.isDisabled()
        && account.securityVersion() == session.securityVersion();
  }

  /**
   * A session whose token predates the stored sessions (only the tests sign such tokens): it is
   * stored now, so the change goes on like any other.
   */
  private StoredSession adopt(String userId, String sidHash, long version, Instant now) {
    Instant absolute = now.plus(policy.absolute());
    Long id = sessions.open(Long.parseLong(userId), sidHash, version, false, absolute, now);
    return new StoredSession(
        id, Long.parseLong(userId), sidHash, version, false, absolute, now, null);
  }

  private IssuedSession issue(
      String userId, String sid, Long sessionId, Instant now, Instant absolute) {
    String secret = randomToken(SECRET_BYTES);
    sessions.addRefreshToken(sessionId, SessionStorePort.hash(secret), now);
    Duration left = Duration.between(now, absolute);
    Duration maxAge = left.compareTo(policy.idle()) < 0 ? left : policy.idle();
    return new IssuedSession(tokens.renew(userId, sid), sid + "." + secret, maxAge);
  }

  private long versionOf(String userId) {
    return users.securityVersion(Long.parseLong(userId));
  }

  private Optional<Parts> parse(String refreshToken) {
    var matcher = REFRESH_TOKEN.matcher(refreshToken);
    return matcher.matches()
        ? Optional.of(new Parts(matcher.group(1), matcher.group(2)))
        : Optional.empty();
  }

  private static String randomToken(int bytes) {
    byte[] raw = new byte[bytes];
    RANDOM.nextBytes(raw);
    return ENCODER.encodeToString(raw);
  }

  /** The two halves of a refresh token. Never printed. */
  private record Parts(String sid, String secret) {
    @Override
    public String toString() {
      return "Parts[***]";
    }
  }
}
