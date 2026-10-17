package com.tarimatwasi.quipu.auth.application;

import com.tarimatwasi.quipu.auth.domain.UserAccount;
import com.tarimatwasi.quipu.auth.port.in.AccountDisabledException;
import com.tarimatwasi.quipu.auth.port.in.ActivateAccessUseCase;
import com.tarimatwasi.quipu.auth.port.in.InvalidResetCodeException;
import com.tarimatwasi.quipu.auth.port.out.AccessGrantRepositoryPort;
import com.tarimatwasi.quipu.auth.port.out.SessionTokenPort;
import com.tarimatwasi.quipu.auth.port.out.UserRepositoryPort;
import com.tarimatwasi.quipu.shared.access.GuestAccessListener;
import java.time.Clock;
import java.time.Instant;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RF-11, CU-24: the public half of the access, the activation link. No role check: whoever holds
 * the single-use token is the guest. Separate from {@link GuestAccessService}, which only the
 * administrator reaches.
 */
@Service
public class AccessActivationService implements ActivateAccessUseCase {

  private final UserRepositoryPort users;
  private final AccessGrantRepositoryPort grants;
  private final GuestAccessListener guests;
  private final PasswordEncoder passwordEncoder;
  private final SessionTokenPort sessionTokens;
  private final Clock clock;

  AccessActivationService(
      UserRepositoryPort users,
      AccessGrantRepositoryPort grants,
      GuestAccessListener guests,
      PasswordEncoder passwordEncoder,
      SessionTokenPort sessionTokens,
      Clock clock) {
    this.users = users;
    this.grants = grants;
    this.guests = guests;
    this.passwordEncoder = passwordEncoder;
    this.sessionTokens = sessionTokens;
    this.clock = clock;
  }

  /**
   * The password is checked before the token is touched, so a weak one can be corrected with the
   * same link. Locks in the order of every access change: the guest, its account, its accesses. The
   * conditional update is what decides: an unknown, used, revoked or expired token gets one answer.
   * The state of the account is checked after it, so a dead token never tells it.
   */
  @Override
  @Transactional
  public ActivationResult activate(String token, String newPassword) {
    PasswordPolicy.require(newPassword);
    String hash = ResetCodeIssuer.hash(token);
    Long guestId = grants.findGuestIdByTokenHash(hash).orElseThrow(InvalidResetCodeException::new);
    guests.lockGuest(guestId);
    UserAccount account =
        users.findByGuestIdForUpdate(guestId).orElseThrow(InvalidResetCodeException::new);
    Instant now = clock.instant();
    if (!grants.consumeLink(hash, now)) {
      throw new InvalidResetCodeException();
    }
    if (account.isDisabled()) {
      throw new AccountDisabledException();
    }
    guests.accessUsed(guestId);
    users.changePassword(account.id(), passwordEncoder.encode(newPassword), clock.instant());
    return new ActivationResult(guestId, sessionTokens.issue(account.id().toString()));
  }
}
