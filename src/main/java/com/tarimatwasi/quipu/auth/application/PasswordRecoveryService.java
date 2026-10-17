package com.tarimatwasi.quipu.auth.application;

import com.tarimatwasi.quipu.auth.port.in.InvalidResetCodeException;
import com.tarimatwasi.quipu.auth.port.in.PasswordRecoveryUseCase;
import com.tarimatwasi.quipu.auth.port.out.AccessGrantRepositoryPort;
import com.tarimatwasi.quipu.auth.port.out.BackgroundTaskPort;
import com.tarimatwasi.quipu.auth.port.out.MailDeliveryException;
import com.tarimatwasi.quipu.auth.port.out.PasswordResetMailPort;
import com.tarimatwasi.quipu.auth.port.out.UserRepositoryPort;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** RF-16: the recovery code is single-use and expires; see {@link ResetCodeIssuer}. */
@Service
public class PasswordRecoveryService implements PasswordRecoveryUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(PasswordRecoveryService.class);

  private final ResetCodeIssuer issuer;
  private final UserRepositoryPort userRepository;
  private final PasswordEncoder passwordEncoder;
  private final PasswordResetMailPort mail;
  private final BackgroundTaskPort background;
  private final AccessGrantRepositoryPort grants;
  private final Clock clock;

  PasswordRecoveryService(
      ResetCodeIssuer issuer,
      UserRepositoryPort userRepository,
      PasswordEncoder passwordEncoder,
      PasswordResetMailPort mail,
      BackgroundTaskPort background,
      AccessGrantRepositoryPort grants,
      Clock clock) {
    this.issuer = issuer;
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
    this.mail = mail;
    this.background = background;
    this.grants = grants;
    this.clock = clock;
  }

  /**
   * Answers at once and does the work in the background, so how long the caller waits does not tell
   * whether the account exists (ADR-F4).
   */
  @Override
  public void requestReset(String email) {
    background.run(() -> issueAndSend(email));
  }

  /** Not transactional on purpose: the code is committed before the email is attempted. */
  private void issueAndSend(String email) {
    issuer
        .issueFor(email)
        .ifPresent(
            issued -> {
              try {
                mail.sendResetLink(
                    Objects.requireNonNull(issued.account().email()),
                    issued.code(),
                    ResetCodeIssuer.CODE_VALIDITY);
              } catch (MailDeliveryException e) {
                // The caller must not learn whether the account exists, so a failed delivery is
                // only logged, without the address or the provider's answer (it can echo the
                // address). The person can ask again once the cooldown passes.
                LOG.warn(
                    "Recovery email for account {} was not delivered: {}",
                    issued.account().id(),
                    e.getMessage());
              }
            });
  }

  /**
   * The row of the code is locked from the lookup to the commit, so of two simultaneous requests
   * with the same code only one finds it.
   */
  @Override
  @Transactional
  public void resetPassword(String code, String newPassword) {
    UserRepositoryPort.PendingReset pending =
        userRepository
            .findPendingReset(ResetCodeIssuer.hash(code))
            .orElseThrow(InvalidResetCodeException::new);
    if (!clock.instant().isBefore(pending.expiresAt())
        || pending.account().isDisabled()
        || ResetCodeIssuer.neverChosePassword(pending.account())) {
      throw new InvalidResetCodeException();
    }
    // Checked after the code and before consuming it: a weak password can be corrected.
    PasswordPolicy.require(newPassword);
    Instant now = clock.instant();
    userRepository.resetPassword(pending.account().id(), passwordEncoder.encode(newPassword), now);
    Long guestId = pending.account().guestId();
    if (guestId != null) {
      // A new password ends any access the administrator handed before.
      grants.revokeLive(guestId, now);
    }
  }
}
