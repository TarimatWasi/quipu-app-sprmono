package com.tarimatwasi.quipu.auth.application;

import com.tarimatwasi.quipu.auth.domain.DocumentType;
import com.tarimatwasi.quipu.auth.domain.UserAccount;
import com.tarimatwasi.quipu.auth.port.in.AccountDisabledException;
import com.tarimatwasi.quipu.auth.port.in.ActivateAccessUseCase;
import com.tarimatwasi.quipu.auth.port.in.GuestAccessUseCase;
import com.tarimatwasi.quipu.auth.port.in.InvalidResetCodeException;
import com.tarimatwasi.quipu.auth.port.out.AccessGrantRepositoryPort;
import com.tarimatwasi.quipu.auth.port.out.AccessMailPort;
import com.tarimatwasi.quipu.auth.port.out.SessionTokenPort;
import com.tarimatwasi.quipu.auth.port.out.UserRepositoryPort;
import com.tarimatwasi.quipu.shared.access.AccessMode;
import com.tarimatwasi.quipu.shared.access.GuestAccessListener;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RF-11, RF-17, RN-32: the accesses of the guests. The link token is 256 random bits and the
 * temporary password 12 characters of a 55-letter alphabet (about 69 bits); of both only the
 * SHA-256 is stored, as with the recovery code ({@link ResetCodeIssuer}): they are not passwords a
 * person chose, they live 30 minutes and the login and the activation are rate limited.
 */
@Service
public class GuestAccessService implements GuestAccessUseCase, ActivateAccessUseCase {

  static final Duration ACCESS_VALIDITY = Duration.ofMinutes(30);

  /** No 0/O, 1/l/I: the password is read aloud or copied by hand. */
  private static final String ALPHABET =
      "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

  private static final int TEMPORARY_PASSWORD_LENGTH = 12;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final UserRepositoryPort users;
  private final AccessGrantRepositoryPort grants;
  private final AccessMailPort mail;
  private final GuestAccessListener guests;
  private final PasswordEncoder passwordEncoder;
  private final SessionTokenPort sessionTokens;
  private final Clock clock;

  GuestAccessService(
      UserRepositoryPort users,
      AccessGrantRepositoryPort grants,
      AccessMailPort mail,
      GuestAccessListener guests,
      PasswordEncoder passwordEncoder,
      SessionTokenPort sessionTokens,
      Clock clock) {
    this.users = users;
    this.grants = grants;
    this.mail = mail;
    this.guests = guests;
    this.passwordEncoder = passwordEncoder;
    this.sessionTokens = sessionTokens;
    this.clock = clock;
  }

  @Override
  @Transactional
  public void openAccount(
      Long guestId, String documentType, String documentNumber, @Nullable String email) {
    if (!users.findAllByGuestIds(List.of(guestId)).isEmpty()) {
      return;
    }
    String free = email != null && users.findByEmail(email).isEmpty() ? email : null;
    users.insertGuestAccount(
        guestId,
        DocumentType.valueOf(documentType),
        documentNumber,
        free,
        passwordEncoder.encode(UUID.randomUUID().toString()));
  }

  @Override
  @Transactional
  public IssuedAccess issueAccess(Long guestId, AccessMode mode) {
    Instant now = clock.instant();
    String secret = mode == AccessMode.LINK ? ResetCodeIssuer.newCode() : temporaryPassword();
    Instant expiresAt = now.plus(ACCESS_VALIDITY);
    grants.revokeLive(guestId, now);
    grants.save(guestId, mode, ResetCodeIssuer.hash(secret), expiresAt);
    String shown = mode == AccessMode.LINK ? mail.activationUrl(secret) : secret;
    return new IssuedAccess(mode, shown, expiresAt, shareMessage(mode, shown));
  }

  @Override
  public boolean sendAccess(String email, IssuedAccess access) {
    return mail.send(email, "Tu acceso a Quipu", access.shareMessage());
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Instant> accessExpiry(Long guestId) {
    return grants.liveExpiry(guestId, clock.instant());
  }

  /**
   * The password is checked before the token is touched, so a weak one can be corrected with the
   * same link. Every token that is not usable fails at the same conditional update, with one
   * answer. The guest row is locked before the account, the order of the deactivation.
   */
  @Override
  @Transactional
  public ActivationResult activate(String token, String newPassword) {
    PasswordPolicy.require(newPassword);
    Instant now = clock.instant();
    Long guestId =
        grants
            .consumeLink(ResetCodeIssuer.hash(token), now)
            .orElseThrow(InvalidResetCodeException::new);
    guests.accessUsed(guestId);
    UserAccount account =
        users.findByGuestIdForUpdate(guestId).orElseThrow(InvalidResetCodeException::new);
    if (account.isDisabled()) {
      throw new AccountDisabledException();
    }
    users.changePassword(account.id(), passwordEncoder.encode(newPassword), clock.instant());
    return new ActivationResult(guestId, sessionTokens.issue(account.id().toString()));
  }

  private static String temporaryPassword() {
    StringBuilder password = new StringBuilder(TEMPORARY_PASSWORD_LENGTH);
    for (int i = 0; i < TEMPORARY_PASSWORD_LENGTH; i++) {
      password.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
    }
    return password.toString();
  }

  static String shareMessage(AccessMode mode, String secret) {
    return mode == AccessMode.LINK
        ? "Hola, este es tu enlace para activar tu acceso a Quipu y elegir tu contraseña:\n"
            + secret
            + "\nVale 30 minutos y solo se puede usar una vez."
        : "Hola, esta es tu contraseña temporal para ingresar a Quipu con tu documento: "
            + secret
            + "\nVale 30 minutos; al ingresar elegirás tu propia contraseña.";
  }
}
