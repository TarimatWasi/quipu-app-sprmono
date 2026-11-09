package com.tarimatwasi.quipu.auth.application.service;

import com.tarimatwasi.quipu.auth.application.port.in.AccountEmailTakenException;
import com.tarimatwasi.quipu.auth.application.port.in.EmailVerificationException;
import com.tarimatwasi.quipu.auth.application.port.in.EmailVerificationException.Reason;
import com.tarimatwasi.quipu.auth.application.port.in.GuestAccessUseCase.AccountEmailChange;
import com.tarimatwasi.quipu.auth.application.port.in.GuestAccessUseCase.EmailChangeOutcome;
import com.tarimatwasi.quipu.auth.application.port.in.GuestEmailVerificationUseCase;
import com.tarimatwasi.quipu.auth.application.port.out.GuestEmailVerificationMailPort;
import com.tarimatwasi.quipu.auth.application.port.out.GuestEmailVerificationStorePort;
import com.tarimatwasi.quipu.auth.application.port.out.GuestEmailVerificationStorePort.PendingEmailVerification;
import com.tarimatwasi.quipu.auth.application.port.out.UserRepositoryPort;
import com.tarimatwasi.quipu.auth.domain.UserAccount;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TAR-349: the guest proves it owns its new email with a six-digit code sent there. Only a hash of
 * the code is kept; a code lives {@link #VALIDITY} and allows {@link #MAX_ATTEMPTS} wrong tries,
 * and a new code waits {@link #COOLDOWN} since the last one. Every step locks the account row of
 * the guest first, the same row the change of the email locks, so two requests of one guest run one
 * after the other.
 */
@Service
@PreAuthorize("hasRole('GUEST')") // BE-SPR-SEC-02: the guest of the session, besides the URL rule
public class GuestEmailVerificationService implements GuestEmailVerificationUseCase {

  static final Duration VALIDITY = Duration.ofMinutes(10);
  static final Duration COOLDOWN = Duration.ofSeconds(60);
  static final int MAX_ATTEMPTS = 5;

  private static final SecureRandom RANDOM = new SecureRandom();

  private final UserRepositoryPort users;
  private final GuestEmailVerificationStorePort store;
  private final GuestEmailVerificationMailPort mail;
  private final GuestAccountEmailChanger emails;
  private final PasswordEncoder passwords;
  private final Clock clock;

  GuestEmailVerificationService(
      UserRepositoryPort users,
      GuestEmailVerificationStorePort store,
      GuestEmailVerificationMailPort mail,
      GuestAccountEmailChanger emails,
      PasswordEncoder passwords,
      Clock clock) {
    this.users = users;
    this.store = store;
    this.mail = mail;
    this.emails = emails;
    this.passwords = passwords;
    this.clock = clock;
  }

  @Override
  @Transactional
  public void request(Long guestId, String email) {
    UserAccount account = lockedAccount(guestId);
    String current = account.email();
    if (current != null && current.equalsIgnoreCase(email)) {
      throw new EmailVerificationException(Reason.UNCHANGED);
    }
    Instant now = clock.instant();
    Optional<PendingEmailVerification> pending = store.find(guestId);
    if (pending.isPresent()) {
      Instant nextAllowed = pending.get().lastSentAt().plus(COOLDOWN);
      if (nextAllowed.isAfter(now)) {
        throw EmailVerificationException.cooldown(Duration.between(now, nextAllowed).toSeconds());
      }
    }
    Optional<UserAccount> owner = users.findByEmail(email);
    if (owner.isPresent() && !owner.get().id().equals(account.id())) {
      throw new AccountEmailTakenException();
    }
    String code = String.format("%06d", RANDOM.nextInt(1_000_000));
    store.replace(guestId, email, passwords.encode(code), now.plus(VALIDITY), now);
    mail.send(email, code, VALIDITY.toMinutes());
  }

  @Override
  @Transactional(noRollbackFor = EmailVerificationException.class)
  public void confirm(Long guestId, String code) {
    lockedAccount(guestId);
    PendingEmailVerification pending =
        store.find(guestId).orElseThrow(() -> new EmailVerificationException(Reason.INVALID_CODE));
    if (!usable(pending)) {
      store.delete(guestId);
      throw new EmailVerificationException(Reason.EXPIRED);
    }
    if (!passwords.matches(code, pending.codeHash())) {
      // Kept on the way out: the transaction does not roll back for this exception.
      store.addFailedAttempt(guestId);
      throw new EmailVerificationException(Reason.INVALID_CODE);
    }
    AccountEmailChange change = emails.change(guestId, pending.email());
    store.delete(guestId);
    if (change.outcome() == EmailChangeOutcome.CHANGED && change.previousEmail() != null) {
      emails.notice(change.previousEmail(), pending.email());
    }
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<String> pendingEmail(Long guestId) {
    return store.find(guestId).filter(this::usable).map(PendingEmailVerification::email);
  }

  private boolean usable(PendingEmailVerification pending) {
    return clock.instant().isBefore(pending.expiresAt()) && pending.attempts() < MAX_ATTEMPTS;
  }

  /** TAR-235: every guest is created with its account, so one without it is corrupt data. */
  private UserAccount lockedAccount(Long guestId) {
    return users
        .findByGuestIdForUpdate(guestId)
        .orElseThrow(() -> new IllegalStateException("The guest " + guestId + " has no account"));
  }
}
