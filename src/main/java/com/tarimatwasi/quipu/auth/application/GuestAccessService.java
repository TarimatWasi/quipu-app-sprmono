package com.tarimatwasi.quipu.auth.application;

import com.tarimatwasi.quipu.auth.domain.DocumentType;
import com.tarimatwasi.quipu.auth.port.in.AccountDocumentTakenException;
import com.tarimatwasi.quipu.auth.port.in.GuestAccessUseCase;
import com.tarimatwasi.quipu.auth.port.out.AccessGrantRepositoryPort;
import com.tarimatwasi.quipu.auth.port.out.AccessMailPort;
import com.tarimatwasi.quipu.auth.port.out.AccountAlreadyExistsException;
import com.tarimatwasi.quipu.auth.port.out.UserRepositoryPort;
import com.tarimatwasi.quipu.shared.access.AccessMode;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
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
@PreAuthorize("hasRole('ADMIN')") // BE-SPR-SEC-02: only the administrator flows hand accesses
public class GuestAccessService implements GuestAccessUseCase {

  static final Duration ACCESS_VALIDITY = Duration.ofMinutes(30);

  /** No 0/O, 1/l/I: the password is read aloud or copied by hand. */
  private static final String ALPHABET =
      "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

  private static final int TEMPORARY_PASSWORD_LENGTH = 12;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final UserRepositoryPort users;
  private final AccessGrantRepositoryPort grants;
  private final AccessMailPort mail;
  private final PasswordEncoder passwordEncoder;
  private final Clock clock;

  GuestAccessService(
      UserRepositoryPort users,
      AccessGrantRepositoryPort grants,
      AccessMailPort mail,
      PasswordEncoder passwordEncoder,
      Clock clock) {
    this.users = users;
    this.grants = grants;
    this.mail = mail;
    this.passwordEncoder = passwordEncoder;
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
    try {
      users.insertGuestAccount(
          guestId,
          DocumentType.valueOf(documentType),
          documentNumber,
          free,
          passwordEncoder.encode(UUID.randomUUID().toString()));
    } catch (AccountAlreadyExistsException e) {
      throw new AccountDocumentTakenException(e);
    }
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
  @Transactional
  public void revokeAccess(Long guestId) {
    grants.revokeLive(guestId, clock.instant());
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Instant> accessExpiry(Long guestId) {
    return grants.liveExpiry(guestId, clock.instant());
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
