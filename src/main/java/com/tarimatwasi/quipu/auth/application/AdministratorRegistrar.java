package com.tarimatwasi.quipu.auth.application;

import com.tarimatwasi.quipu.auth.domain.DocumentType;
import com.tarimatwasi.quipu.auth.domain.Role;
import com.tarimatwasi.quipu.auth.domain.UserAccount;
import com.tarimatwasi.quipu.auth.port.in.AdministratorDocumentTakenException;
import com.tarimatwasi.quipu.auth.port.in.AdministratorEmailTakenException;
import com.tarimatwasi.quipu.auth.port.in.AdministratorLimitReachedException;
import com.tarimatwasi.quipu.auth.port.in.AdministratorReauthenticationException;
import com.tarimatwasi.quipu.auth.port.out.AccountTakenException;
import com.tarimatwasi.quipu.auth.port.out.AdministratorStorePort;
import com.tarimatwasi.quipu.auth.port.out.AdministratorStorePort.NewAdministrator;
import com.tarimatwasi.quipu.auth.port.out.UserRepositoryPort;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The database half of RF-22: re-authenticates the creator and stores the new administrator, in one
 * transaction. It is its own bean so that the transaction ends before any email is sent: the link
 * is issued for a committed account, and the connection is not held while the provider answers.
 */
@Service
class AdministratorRegistrar {

  /** What to register; the document is already normalized and the hash already computed. */
  record Registration(
      Long creatorId,
      DocumentType documentType,
      String documentNumber,
      String email,
      String currentPassword,
      String unusablePasswordHash) {}

  /** The new account and the email of who created it, for the notices. */
  record Registered(Long id, String creatorEmail) {}

  private final UserRepositoryPort users;
  private final AdministratorStorePort store;
  private final PasswordEncoder passwordEncoder;
  private final Clock clock;
  private final int maxAdministrators;

  AdministratorRegistrar(
      UserRepositoryPort users,
      AdministratorStorePort store,
      PasswordEncoder passwordEncoder,
      Clock clock,
      @Value("${app.administrators.max:5}") int maxAdministrators) {
    if (maxAdministrators < 1) {
      throw new IllegalArgumentException("app.administrators.max must be at least 1");
    }
    this.users = users;
    this.store = store;
    this.passwordEncoder = passwordEncoder;
    this.clock = clock;
    this.maxAdministrators = maxAdministrators;
  }

  /**
   * The failed attempt is saved even though the creation ends in an exception, hence the {@code
   * noRollbackFor}. Lock order: the row of the creator (as a login of it), then the lock of the
   * administrators, so the slow password check never holds the latter.
   */
  @Transactional(noRollbackFor = AdministratorReauthenticationException.class)
  Registered register(Registration registration) {
    UserAccount creator =
        users
            .findByIdForUpdate(registration.creatorId())
            .orElseThrow(AdministratorReauthenticationException::new);
    if (creator.role() != Role.ADMIN || creator.isDisabled()) {
      throw new AccessDeniedException("Only an active administrator creates administrators");
    }
    // The URL rules already refuse it (RF-12); a call that skips them is refused here too.
    if (creator.mustChangePassword()) {
      throw new AccessDeniedException("The session must change its password first");
    }
    reauthenticate(creator, registration.currentPassword());
    store.lockAdministrators();
    if (store.countAdministrators() >= maxAdministrators) {
      throw new AdministratorLimitReachedException();
    }
    Long id;
    try {
      id =
          store.insert(
              new NewAdministrator(
                  registration.documentType(),
                  registration.documentNumber(),
                  registration.email(),
                  registration.unusablePasswordHash(),
                  creator.id()));
    } catch (AccountTakenException e) {
      throw switch (e.taken()) {
        case DOCUMENT -> new AdministratorDocumentTakenException();
        case EMAIL -> new AdministratorEmailTakenException();
      };
    }
    return new Registered(id, Objects.requireNonNull(creator.email()));
  }

  /**
   * SEG-06: a wrong password counts as a failed login of the creator and the fifth in a row locks
   * its login, as at the login. While the lock lasts the creator cannot re-authenticate either, so
   * a stolen session cannot keep guessing here; its session and every other route keep working.
   */
  private void reauthenticate(UserAccount creator, String currentPassword) {
    Instant now = clock.instant();
    if (creator.isLockedAt(now)) {
      throw new AdministratorReauthenticationException();
    }
    if (!passwordEncoder.matches(currentPassword, creator.passwordHash())) {
      users.registerFailedLogin(
          creator.id(),
          now,
          AuthApplicationService.MAX_FAILED_LOGINS,
          AuthApplicationService.LOCK_DURATION);
      throw new AdministratorReauthenticationException();
    }
    if (creator.failedLoginAttempts() > 0 || creator.lockedUntil() != null) {
      users.clearFailedLogins(creator.id());
    }
  }
}
