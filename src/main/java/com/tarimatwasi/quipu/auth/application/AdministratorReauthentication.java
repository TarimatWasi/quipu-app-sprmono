package com.tarimatwasi.quipu.auth.application;

import com.tarimatwasi.quipu.auth.domain.Role;
import com.tarimatwasi.quipu.auth.domain.UserAccount;
import com.tarimatwasi.quipu.auth.port.in.AdministratorReauthenticationException;
import com.tarimatwasi.quipu.auth.port.out.UserRepositoryPort;
import java.time.Clock;
import java.time.Instant;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The re-authentication of an administrator who creates (RF-22), deactivates or reactivates
 * (TAR-127) another one: the session must be of an active administrator that is not pending its
 * password change, and {@code currentPassword} must be its password. A wrong password counts for
 * SEG-06 like a failed login.
 */
@Component
class AdministratorReauthentication {

  private final UserRepositoryPort users;
  private final PasswordEncoder passwordEncoder;
  private final Clock clock;

  AdministratorReauthentication(
      UserRepositoryPort users, PasswordEncoder passwordEncoder, Clock clock) {
    this.users = users;
    this.passwordEncoder = passwordEncoder;
    this.clock = clock;
  }

  /**
   * Locks the row of the actor (as a login of it) and checks its password. Joins the transaction of
   * the caller or opens its own; the failed attempt is saved even though it ends in an exception,
   * hence the {@code noRollbackFor}.
   *
   * @return the actor, as read under the lock
   * @throws AdministratorReauthenticationException if the password is wrong or the login of the
   *     actor is locked by failed attempts
   * @throws AccessDeniedException if the session is not of an active administrator or is pending
   *     its password change
   */
  @Transactional(noRollbackFor = AdministratorReauthenticationException.class)
  public UserAccount reauthenticate(Long actorId, String currentPassword) {
    UserAccount actor =
        users.findByIdForUpdate(actorId).orElseThrow(AdministratorReauthenticationException::new);
    requireActiveAdministrator(actor);
    checkPassword(actor, currentPassword);
    return actor;
  }

  /**
   * The URL rules already refuse another role and a pending password change (RN-08, RF-12); a call
   * that skips them is refused here too, and so is an account deactivated meanwhile.
   */
  static void requireActiveAdministrator(UserAccount actor) {
    if (actor.role() != Role.ADMIN || actor.isDisabled()) {
      throw new AccessDeniedException("Only an active administrator manages administrators");
    }
    if (actor.mustChangePassword()) {
      throw new AccessDeniedException("The session must change its password first");
    }
  }

  /**
   * SEG-06: a wrong password counts as a failed login of the actor and the fifth in a row locks its
   * login, as at the login. While the lock lasts the actor cannot re-authenticate either, so a
   * stolen session cannot keep guessing here; its session and every other route keep working.
   */
  private void checkPassword(UserAccount actor, String currentPassword) {
    Instant now = clock.instant();
    if (actor.isLockedAt(now)) {
      throw new AdministratorReauthenticationException();
    }
    if (!passwordEncoder.matches(currentPassword, actor.passwordHash())) {
      users.registerFailedLogin(
          actor.id(),
          now,
          AuthApplicationService.MAX_FAILED_LOGINS,
          AuthApplicationService.LOCK_DURATION);
      throw new AdministratorReauthenticationException();
    }
    if (actor.failedLoginAttempts() > 0 || actor.lockedUntil() != null) {
      users.clearFailedLogins(actor.id());
    }
  }
}
