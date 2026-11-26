package com.tarimatwasi.quipu.auth.application.service;

import com.tarimatwasi.quipu.auth.application.port.out.AdministratorStorePort;
import com.tarimatwasi.quipu.auth.application.port.out.AdministratorStorePort.NewAdministrator;
import com.tarimatwasi.quipu.auth.application.port.out.SessionStorePort;
import com.tarimatwasi.quipu.auth.application.port.out.SessionStorePort.RevokedReason;
import com.tarimatwasi.quipu.auth.application.port.out.SessionStorePort.StoredSession;
import com.tarimatwasi.quipu.auth.application.port.out.UserRepositoryPort;
import com.tarimatwasi.quipu.auth.application.port.out.UserRepositoryPort.FailedLogin;
import com.tarimatwasi.quipu.auth.domain.event.AccountLocked;
import com.tarimatwasi.quipu.auth.domain.event.AccountsDeactivated;
import com.tarimatwasi.quipu.auth.domain.event.AdministratorCreated;
import com.tarimatwasi.quipu.auth.domain.event.AdministratorReactivated;
import com.tarimatwasi.quipu.auth.domain.event.CredentialsChanged;
import com.tarimatwasi.quipu.auth.domain.event.PasswordResetIssued;
import com.tarimatwasi.quipu.auth.domain.event.SessionEnd;
import com.tarimatwasi.quipu.auth.domain.event.SessionOpened;
import com.tarimatwasi.quipu.auth.domain.event.SessionRevoked;
import com.tarimatwasi.quipu.shared.events.PublishEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The security changes of an account, each one with the fact it states. They live apart from the
 * flows that cause them because those flows often go on to throw (a reused refresh token, a wrong
 * password) in a transaction that still commits: the fact is published when this change returns,
 * inside that same transaction, so the exception that follows cannot lose it. Every method joins
 * the caller's transaction.
 */
@Service
public class SecurityFacts {
  private final SessionStorePort sessions;
  private final UserRepositoryPort users;
  private final AdministratorStorePort administrators;

  SecurityFacts(
      SessionStorePort sessions, UserRepositoryPort users, AdministratorStorePort administrators) {
    this.sessions = sessions;
    this.users = users;
    this.administrators = administrators;
  }

  /** Opens a session of the account; returns its id. */
  @Transactional
  @PublishEvent(
      type = SessionOpened.class,
      args = {"#result", "#userId", "#temporary"})
  public Long openSession(
      Long userId,
      String sidHash,
      long securityVersion,
      boolean temporary,
      Instant absoluteExpiresAt,
      Instant now) {
    return sessions.open(userId, sidHash, securityVersion, temporary, absoluteExpiresAt, now);
  }

  /** Stores the hash of a new recovery code; the code itself never leaves its flow. */
  @Transactional
  @PublishEvent(
      type = PasswordResetIssued.class,
      args = {"#userId", "#expiresAt"})
  public void issueResetCode(Long userId, String codeHash, Instant expiresAt) {
    users.saveResetToken(userId, codeHash, expiresAt);
  }

  /** Stores a new administrator (RF-22); returns its id. */
  @Transactional
  @PublishEvent(
      type = AdministratorCreated.class,
      args = {"#result"})
  public Long createAdministrator(NewAdministrator administrator) {
    return administrators.insert(administrator);
  }

  /** Closes one session. */
  @Transactional
  @PublishEvent(
      type = SessionRevoked.class,
      args = {"#session.id()", "#session.userId()", "#cause"})
  public void revoke(StoredSession session, SessionEnd cause, Instant at) {
    sessions.revoke(session.id(), RevokedReason.valueOf(cause.name()), at);
  }

  /** After a password change only the session that changed it goes on (TAR-227). */
  @Transactional
  @PublishEvent(
      type = CredentialsChanged.class,
      args = {"#kept.userId()", "#version", "#kept.id()"})
  public void keepOnly(StoredSession kept, long version, Instant at) {
    sessions.revokeOthers(kept.userId(), kept.sidHash(), RevokedReason.PASSWORD_CHANGE, at);
  }

  /** A recovery by code: the new password ends every session. */
  @Transactional
  @PublishEvent(
      type = CredentialsChanged.class,
      args = {"#userId", "#result", "null"})
  public long resetPassword(
      Long userId, String passwordHash, Instant at, @Nullable String activationName) {
    return activationName == null
        ? users.resetPassword(userId, passwordHash, at)
        : users.resetPasswordAndName(userId, passwordHash, at, activationName);
  }

  /** Counts a failed attempt; the one that reaches the limit locks the account. */
  @Transactional
  @PublishEvent(
      type = AccountLocked.class,
      when = "!#result.alreadyLocked() && #result.lockedNow()",
      args = {"#userId"})
  public FailedLogin failedLogin(Long userId, Instant now, int maxAttempts, Duration lock) {
    return users.registerFailedLogin(userId, now, maxAttempts, lock);
  }

  /** Enables or disables the account of a guest; a deactivation ends its sessions (RN-12). */
  @Transactional
  @PublishEvent(
      type = AccountsDeactivated.class,
      when = "!#result.isEmpty()",
      args = {"#result"})
  public List<Long> setGuestAccountActive(Long guestId, boolean active) {
    return users.setGuestAccountStatus(guestId, active ? "ACTIVE" : "INACTIVE");
  }

  /** Sets the status of an administrator; a deactivation ends its sessions. */
  @Transactional
  @PublishEvent(
      type = AdministratorReactivated.class,
      when = "#status == 'ACTIVE'",
      args = {"#administratorId"})
  @PublishEvent(
      type = AccountsDeactivated.class,
      when = "#status == 'INACTIVE'",
      args = {"{#administratorId}"})
  public void setAdministratorStatus(Long administratorId, String status, Long modifiedBy) {
    administrators.setAdministratorStatus(administratorId, status, modifiedBy);
  }
}
