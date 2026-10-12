package com.tarimatwasi.quipu.auth.application;

import com.tarimatwasi.quipu.auth.domain.UserAccount;
import com.tarimatwasi.quipu.auth.port.out.UserRepositoryPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The database half of a recovery request (RF-16): decides whether a code is issued and stores its
 * hash. It is its own bean so that the transaction ends before the email is sent: the connection is
 * not held while the email provider answers, and an email is never sent for a code that was not
 * committed.
 *
 * <p>The code is 256 random bits and only its SHA-256 is stored (SEG-05). A fast hash is enough
 * because the code is not a password a person chose: it cannot be guessed, and the lookup is by
 * hash.
 */
@Service
class ResetCodeIssuer {

  static final Duration CODE_VALIDITY = Duration.ofMinutes(30);
  private static final int CODE_BYTES = 32;

  /** A code that was issued and still has to be emailed. */
  record IssuedCode(UserAccount account, String code) {}

  private final SecureRandom random = new SecureRandom();
  private final UserRepositoryPort userRepository;
  private final Clock clock;

  ResetCodeIssuer(UserRepositoryPort userRepository, Clock clock) {
    this.userRepository = userRepository;
    this.clock = clock;
  }

  /**
   * Issues a code for an active account that has no live one (never had one, it expired, or it was
   * used or cleared by a password change). While a code is live nothing is issued or sent: only its
   * hash is stored, so it cannot be resent, and replacing it would let anyone who knows the email
   * invalidate the link the person just received (TAR-181). This also bounds the emails to one per
   * validity period. The account row is locked until the commit, so two simultaneous requests
   * cannot both issue a code.
   */
  @Transactional
  Optional<IssuedCode> issueFor(String email) {
    Optional<UserAccount> account = userRepository.findByEmail(email.strip());
    if (account.isEmpty() || account.get().isDisabled()) {
      return Optional.empty();
    }
    UserAccount user = account.get();
    Instant now = clock.instant();
    if (hasLiveCode(user, now)) {
      return Optional.empty();
    }
    String code = newCode();
    userRepository.saveResetToken(user.id(), hash(code), now.plus(CODE_VALIDITY));
    return Optional.of(new IssuedCode(user, code));
  }

  /** A code is live while it has not expired; used codes are removed, so they have no expiry. */
  private boolean hasLiveCode(UserAccount user, Instant now) {
    return userRepository.findResetTokenExpiry(user.id()).map(now::isBefore).orElse(false);
  }

  private String newCode() {
    byte[] bytes = new byte[CODE_BYTES];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  static String hash(String code) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("Every Java platform provides SHA-256", e);
    }
  }
}
