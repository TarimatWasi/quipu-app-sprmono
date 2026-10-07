package com.tarimatwasi.quipu.auth.application;

import com.tarimatwasi.quipu.auth.domain.DocumentType;
import com.tarimatwasi.quipu.auth.domain.UserAccount;
import com.tarimatwasi.quipu.auth.port.in.AccountDisabledException;
import com.tarimatwasi.quipu.auth.port.in.AccountLockedException;
import com.tarimatwasi.quipu.auth.port.in.ChangePasswordUseCase;
import com.tarimatwasi.quipu.auth.port.in.CurrentSessionUseCase;
import com.tarimatwasi.quipu.auth.port.in.InvalidCredentialsException;
import com.tarimatwasi.quipu.auth.port.in.LoginUseCase;
import com.tarimatwasi.quipu.auth.port.in.NoActiveSessionException;
import com.tarimatwasi.quipu.auth.port.in.PasswordUnchangedException;
import com.tarimatwasi.quipu.auth.port.out.SessionTokenPort;
import com.tarimatwasi.quipu.auth.port.out.UserRepositoryPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthApplicationService
    implements LoginUseCase, ChangePasswordUseCase, CurrentSessionUseCase {

  private static final String DUMMY_PASSWORD_HASH =
      "$2b$10$izcb2KSDHR.LLCnLSqUYZ.2cp1yucRUSMDq0Eo9HEg4LQaSzNfmEC";

  /** SEG-06: consecutive failed logins that lock the account, and for how long. */
  private static final int MAX_FAILED_LOGINS = 5;

  private static final Duration LOCK_DURATION = Duration.ofMinutes(15);

  private final UserRepositoryPort userRepository;
  private final PasswordEncoder passwordEncoder;
  private final SessionTokenPort sessionTokens;
  private final Clock clock;

  public AuthApplicationService(
      UserRepositoryPort userRepository,
      PasswordEncoder passwordEncoder,
      SessionTokenPort sessionTokens,
      Clock clock) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
    this.sessionTokens = sessionTokens;
    this.clock = clock;
  }

  /**
   * The failed-attempt count is saved even though the login ends in an exception, hence the {@code
   * noRollbackFor}.
   */
  @Override
  @Transactional(
      noRollbackFor = {
        InvalidCredentialsException.class,
        AccountLockedException.class,
        AccountDisabledException.class
      })
  public LoginResult login(LoginCommand command) {
    // The account is read under its row lock: simultaneous logins of one account (a burst of
    // guesses) are decided one by one, so none can slip past the lock with a stale reading.
    var userOpt =
        userRepository.findByDocumentForUpdate(
            DocumentType.valueOf(command.documentType().name()), command.documentNumber());
    UserAccount user = userOpt.orElse(null);
    String passwordHashToCheck = user != null ? user.passwordHash() : DUMMY_PASSWORD_HASH;

    // Always perform password check to prevent timing attacks
    boolean passwordMatches = passwordEncoder.matches(command.rawPassword(), passwordHashToCheck);
    if (user == null) {
      throw new InvalidCredentialsException();
    }
    Instant now = clock.instant();
    // A locked account neither counts attempts nor reveals whether the password was right.
    if (user.isLockedAt(now)) {
      throw new AccountLockedException(now, Objects.requireNonNull(user.lockedUntil()));
    }
    if (!passwordMatches) {
      // Defensive: the row is already locked, but the repository still answers if it was locked.
      boolean alreadyLocked =
          userRepository.registerFailedLogin(user.id(), now, MAX_FAILED_LOGINS, LOCK_DURATION);
      // The row was read under its lock, so this can only be a stale reading; the longest a lock
      // can still last is the full duration, which is what the client is told.
      throw alreadyLocked
          ? new AccountLockedException(now, now.plus(LOCK_DURATION))
          : new InvalidCredentialsException();
    }
    if (user.isDisabled()) {
      throw new AccountDisabledException();
    }
    if (user.failedLoginAttempts() > 0 || user.lockedUntil() != null) {
      userRepository.clearFailedLogins(user.id());
    }
    String role = user.role().name();
    return new LoginResult(
        user.id().toString(),
        role,
        user.email(),
        user.mustChangePassword(),
        sessionTokens.issue(user.id().toString(), role, user.mustChangePassword()));
  }

  @Override
  @Transactional
  public ChangePasswordResult changePassword(ChangePasswordCommand command) {
    UserAccount user = findAccountForUpdate(command.userId());
    if (user.isDisabled()) {
      throw new AccountDisabledException();
    }
    requireCurrentPassword(user, command.currentPassword());
    PasswordPolicy.require(command.newPassword());
    if (passwordEncoder.matches(command.newPassword(), user.passwordHash())) {
      throw new PasswordUnchangedException();
    }
    // The cutoff is taken after the (slow) hash: a token issued before it stops being valid.
    String newHash = passwordEncoder.encode(command.newPassword());
    userRepository.changePassword(user.id(), newHash, clock.instant());
    return new ChangePasswordResult(
        sessionTokens.issue(command.userId(), user.role().name(), false));
  }

  @Override
  @Transactional(readOnly = true)
  public CurrentSession currentSession(String userId) {
    UserAccount user =
        lookup(userId)
            .filter(account -> !account.isDisabled())
            .orElseThrow(NoActiveSessionException::new);
    return new CurrentSession(user.role().name(), user.email(), user.mustChangePassword());
  }

  /**
   * The id comes from the session, but a malformed or unknown one is just another bad session. The
   * row stays locked until the end of the transaction.
   */
  private UserAccount findAccountForUpdate(String userId) {
    try {
      return userRepository
          .findByIdForUpdate(UUID.fromString(userId))
          .orElseThrow(InvalidCredentialsException::new);
    } catch (IllegalArgumentException e) {
      throw new InvalidCredentialsException();
    }
  }

  private Optional<UserAccount> lookup(String userId) {
    try {
      return userRepository.findById(UUID.fromString(userId));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  /** Optional only while the account must change its password: that session just logged in. */
  private void requireCurrentPassword(UserAccount user, @Nullable String currentPassword) {
    boolean matches =
        currentPassword != null && passwordEncoder.matches(currentPassword, user.passwordHash());
    boolean skippable = currentPassword == null && user.mustChangePassword();
    if (!matches && !skippable) {
      throw new InvalidCredentialsException();
    }
  }
}
