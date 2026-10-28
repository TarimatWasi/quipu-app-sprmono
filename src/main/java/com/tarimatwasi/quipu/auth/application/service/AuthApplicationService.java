package com.tarimatwasi.quipu.auth.application.service;

import com.tarimatwasi.quipu.auth.application.port.in.AccountDisabledException;
import com.tarimatwasi.quipu.auth.application.port.in.AccountLockedException;
import com.tarimatwasi.quipu.auth.application.port.in.ChangePasswordUseCase;
import com.tarimatwasi.quipu.auth.application.port.in.CurrentSessionUseCase;
import com.tarimatwasi.quipu.auth.application.port.in.InvalidCredentialsException;
import com.tarimatwasi.quipu.auth.application.port.in.LoginUseCase;
import com.tarimatwasi.quipu.auth.application.port.in.NoActiveSessionException;
import com.tarimatwasi.quipu.auth.application.port.in.PasswordUnchangedException;
import com.tarimatwasi.quipu.auth.application.port.in.TemporaryPasswordExpiredException;
import com.tarimatwasi.quipu.auth.application.port.in.TooManyLoginAttemptsException;
import com.tarimatwasi.quipu.auth.application.port.out.AccessGrantRepositoryPort;
import com.tarimatwasi.quipu.auth.application.port.out.AccessGrantRepositoryPort.LiveTemporaryPassword;
import com.tarimatwasi.quipu.auth.application.port.out.LoginAttemptLimiterPort;
import com.tarimatwasi.quipu.auth.application.port.out.SessionTokenPort;
import com.tarimatwasi.quipu.auth.application.port.out.UserRepositoryPort;
import com.tarimatwasi.quipu.auth.domain.DocumentType;
import com.tarimatwasi.quipu.auth.domain.UserAccount;
import com.tarimatwasi.quipu.shared.access.GuestAccessListener;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

  /**
   * SEG-06: consecutive failed logins that lock the account, and for how long. The
   * re-authentication of RF-22 counts against the same limit ({@link AdministratorRegistrar}).
   */
  static final int MAX_FAILED_LOGINS = 5;

  static final Duration LOCK_DURATION = Duration.ofMinutes(15);

  private final UserRepositoryPort userRepository;
  private final PasswordEncoder passwordEncoder;
  private final SessionTokenPort sessionTokens;
  private final LoginAttemptLimiterPort loginAttempts;
  private final AccessGrantRepositoryPort grants;
  private final GuestAccessListener guests;
  private final Clock clock;

  /** Hashed once at startup (CFG-06, SEC-06) so an unknown document costs one bcrypt check too. */
  private final String dummyPasswordHash;

  public AuthApplicationService(
      UserRepositoryPort userRepository,
      PasswordEncoder passwordEncoder,
      SessionTokenPort sessionTokens,
      LoginAttemptLimiterPort loginAttempts,
      AccessGrantRepositoryPort grants,
      GuestAccessListener guests,
      Clock clock) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
    this.sessionTokens = sessionTokens;
    this.loginAttempts = loginAttempts;
    this.grants = grants;
    this.guests = guests;
    this.clock = clock;
    this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
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
    // SEC-01 (TAR-124): the attempts on one account are capped whatever the address they come from,
    // and before the account is read or any password is hashed.
    long wait = loginAttempts.tryAcquire(command.documentType().name(), command.documentNumber());
    if (wait > 0) {
      throw new TooManyLoginAttemptsException(wait);
    }
    // The account is read under its row lock: simultaneous logins of one account (a burst of
    // guesses) are decided one by one, so none can slip past the lock with a stale reading.
    var userOpt =
        userRepository.findByDocumentForUpdate(
            DocumentType.valueOf(command.documentType().name()), command.documentNumber());
    UserAccount user = userOpt.orElse(null);
    String passwordHashToCheck = user != null ? user.passwordHash() : dummyPasswordHash;

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
    LiveTemporaryPassword temporary =
        passwordMatches ? null : matchingTemporaryPassword(user, command.rawPassword());
    if (!passwordMatches && temporary == null) {
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
    return sessionOf(user, temporary);
  }

  /**
   * RF-12, D-30: a temporary password opens a session marked as such, which may only choose the
   * password. Nothing else changes here: the access is consumed and the guest starts its onboarding
   * when it chooses the password, so a guest that leaves before can sign in again with it until it
   * expires. The account is not flagged, so earlier sessions keep asking for the current password.
   */
  private LoginResult sessionOf(UserAccount user, @Nullable LiveTemporaryPassword temporary) {
    String userId = user.id().toString();
    return new LoginResult(
        userId,
        user.role().name(),
        user.email(),
        user.guestId(),
        temporary != null || user.mustChangePassword(),
        temporary == null
            ? sessionTokens.issue(userId)
            : sessionTokens.issueTemporary(userId, temporary.id(), temporary.expiresAt()));
  }

  @Override
  @Transactional
  public ChangePasswordResult changePassword(ChangePasswordCommand command) {
    // Lock order of the accesses: the guest, then its account, then its accesses.
    // A scalar read: no account enters the persistence context before the guest lock is held, so
    // the account read under its own lock below is fresh (a deactivation committed meanwhile
    // shows).
    Long guestId = guestOf(command.userId());
    if (guestId != null) {
      guests.lockGuest(guestId);
    }
    UserAccount user = findAccountForUpdate(command.userId());
    if (user.isDisabled()) {
      throw new AccountDisabledException();
    }
    requireCurrentPassword(user, command);
    PasswordPolicy.require(command.newPassword());
    if (passwordEncoder.matches(command.newPassword(), user.passwordHash())) {
      throw new PasswordUnchangedException();
    }
    PasswordPolicy.requireNotALiveAccess(grants, guestId, command.newPassword(), clock.instant());
    // The cutoff is taken after the (slow) hash: a token issued before it stops being valid.
    String newHash = passwordEncoder.encode(command.newPassword());
    // TAR-205: to the microsecond, as TIMESTAMPTZ stores it, so the database rounds nothing.
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    userRepository.changePassword(user.id(), newHash, now);
    if (guestId != null) {
      // D-30: the temporary password is used up when the guest chooses its own, and the guest
      // starts its onboarding; any other live access stops working too.
      grants.consumeTemporaryPassword(guestId, now);
      grants.revokeLive(guestId, now);
      guests.accessUsed(guestId);
    }
    return new ChangePasswordResult(sessionTokens.issue(command.userId()));
  }

  @Override
  @Transactional(readOnly = true)
  public CurrentSession currentSession(String userId) {
    UserAccount user =
        lookup(userId)
            .filter(account -> !account.isDisabled())
            .orElseThrow(NoActiveSessionException::new);
    return new CurrentSession(
        user.role().name(), user.email(), user.guestId(), user.mustChangePassword());
  }

  /**
   * The id comes from the session, but a malformed or unknown one is just another bad session. The
   * row stays locked until the end of the transaction.
   */
  private UserAccount findAccountForUpdate(String userId) {
    try {
      return userRepository
          .findByIdForUpdate(Long.parseLong(userId))
          .orElseThrow(InvalidCredentialsException::new);
    } catch (IllegalArgumentException e) {
      throw new InvalidCredentialsException();
    }
  }

  private Optional<UserAccount> lookup(String userId) {
    try {
      return userRepository.findById(Long.parseLong(userId));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  /**
   * RN-32: a guest may also sign in with the live temporary password of its access, compared by
   * hash in constant time. Right but expired gives its own answer; it is only reached when the
   * password matched, so it never tells whether a document exists.
   */
  private @Nullable LiveTemporaryPassword matchingTemporaryPassword(
      UserAccount user, String rawPassword) {
    Long guestId = user.guestId();
    if (guestId == null) {
      return null;
    }
    LiveTemporaryPassword live = grants.findLiveTemporaryPassword(guestId).orElse(null);
    if (live == null || !sameHash(rawPassword, live)) {
      return null;
    }
    if (!clock.instant().isBefore(live.expiresAt())) {
      throw new TemporaryPasswordExpiredException();
    }
    return live;
  }

  private @Nullable Long guestOf(String userId) {
    try {
      return userRepository.findGuestIdOf(Long.parseLong(userId)).orElse(null);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** Constant-time comparison of the SHA-256 of the password with the stored one. */
  private static boolean sameHash(String rawPassword, LiveTemporaryPassword live) {
    return MessageDigest.isEqual(
        ResetCodeIssuer.hash(rawPassword).getBytes(StandardCharsets.US_ASCII),
        live.tokenHash().getBytes(StandardCharsets.US_ASCII));
  }

  /**
   * The current password may be left out by the session of a guest account only if it was opened
   * with a temporary password that is still live (not used nor revoked, RF-12): earlier sessions,
   * and a temporary session whose access was revoked, must give it. An account of no guest (the
   * initial administrator) may leave it out while it must replace its first password, which it got
   * before having any session.
   */
  private void requireCurrentPassword(UserAccount user, ChangePasswordCommand command) {
    String currentPassword = command.currentPassword();
    boolean matches =
        currentPassword != null && passwordEncoder.matches(currentPassword, user.passwordHash());
    Long guestId = user.guestId();
    Long grantId = command.temporaryGrantId();
    boolean exempt =
        guestId == null
            ? user.mustChangePassword()
            : grantId != null && grants.isLiveTemporaryPassword(grantId, guestId, clock.instant());
    boolean skippable = currentPassword == null && exempt;
    if (!matches && !skippable) {
      throw new InvalidCredentialsException();
    }
  }
}
