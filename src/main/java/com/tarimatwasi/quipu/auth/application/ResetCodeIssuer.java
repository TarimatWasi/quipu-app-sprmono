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

  /** A live code issued less than this ago is kept: the next request does not replace it. */
  static final Duration KEEP_WINDOW = Duration.ofMinutes(5);

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
   * Issues a code for an active account unless its current code was issued less than {@link
   * #KEEP_WINDOW} ago. Inside the window nothing is issued or sent (only the hash is stored, so the
   * code cannot be resent); after it a request replaces the code, so a person whose email never
   * arrived can retry, while anyone who knows the email can invalidate the link at most once per
   * window (TAR-181). Expired, used or cleared codes have no live issue time and are replaced. The
   * account row is locked until the commit, so two simultaneous requests cannot both issue a code.
   */
  @Transactional
  Optional<IssuedCode> issueFor(String email) {
    Optional<UserAccount> account = userRepository.findByEmail(email.strip());
    if (account.isEmpty() || account.get().isDisabled()) {
      return Optional.empty();
    }
    UserAccount user = account.get();
    Instant now = clock.instant();
    if (issuedRecently(user, now)) {
      return Optional.empty();
    }
    String code = newCode();
    userRepository.saveResetToken(user.id(), hash(code), now.plus(CODE_VALIDITY));
    return Optional.of(new IssuedCode(user, code));
  }

  /**
   * The issue time is the expiry minus the validity, so no column is needed. ponytail: breaks if
   * CODE_VALIDITY changes while codes are in flight; store the issue time then.
   */
  private boolean issuedRecently(UserAccount user, Instant now) {
    return userRepository
        .findResetTokenExpiry(user.id())
        .map(expiry -> now.isBefore(expiry.minus(CODE_VALIDITY).plus(KEEP_WINDOW)))
        .orElse(false);
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
