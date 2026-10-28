package com.tarimatwasi.quipu.auth.infrastructure.adapter.out.security;

import com.tarimatwasi.quipu.auth.application.port.out.SessionTokenPort;
import com.tarimatwasi.quipu.shared.access.AccessSessions;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * Issues the session token. Reading it is Spring Security's job ({@link SessionJwt#decoder} and
 * {@link AccountJwtAuthenticationConverter}). The subject is the masked id of the user: the browser
 * can read the token and must not see the sequential id.
 *
 * <p>TAR-227: every token names its session ({@link AccessSessions#SESSION_ID_CLAIM}), 128 bits
 * from {@link SecureRandom} chosen when the session opens and kept when it is renewed. The subject
 * is masked with the key of that session, given here by its id: the token is being issued, so no
 * request carries the session yet. The id is the family a future refresh token rotates in.
 */
@Component
public class JwtTokenProvider implements SessionTokenPort {

  /**
   * TAR-201 (D-20): the issue time in microseconds since the epoch, rounded up. The standard {@code
   * iat} has only the second, which let a token issued earlier in the second of a password change
   * outlive it. Rounded up, a token issued at or after the change never compares before it, however
   * the database rounds the stored instant to its microsecond.
   */
  static final String ISSUED_AT_MICROS_CLAIM = "iat_us";

  /** Thread-safe; one for the whole process, so the sessions draw from one seeded source. */
  private static final SecureRandom RANDOM = new SecureRandom();

  private static final Base64.Encoder SESSION_ID_ENCODER = Base64.getUrlEncoder().withoutPadding();

  private final JwtEncoder encoder;
  private final Duration expiration;
  private final Clock clock;
  private final IdMasker masker;

  public JwtTokenProvider(
      JwtProperties properties, Clock clock, JwtEncoder encoder, IdMasker masker) {
    this.encoder = encoder;
    this.expiration = properties.expiration();
    this.clock = clock;
    this.masker = masker;
  }

  @Override
  public String issue(String userId) {
    return renew(userId, newSessionId());
  }

  @Override
  public String renew(String userId, String sessionId) {
    Instant now = clock.instant();
    return encode(builder(userId, sessionId, now).expiresAt(now.plus(expiration)).build());
  }

  /** The access id is signed like the rest of the token, so the client cannot swap it. */
  @Override
  public String issueTemporary(String userId, Long grantId, Instant expiresAt) {
    Instant now = clock.instant();
    return encode(
        builder(userId, newSessionId(), now)
            .expiresAt(expiresAt)
            .claim(AccessSessions.TEMPORARY_CLAIM, grantId.toString())
            .build());
  }

  private JwtClaimsSet.Builder builder(String userId, String sessionId, Instant now) {
    return JwtClaimsSet.builder()
        .subject(masker.session(sessionId).mask(IdKind.USER, Long.parseLong(userId)).toString())
        .claim(AccessSessions.SESSION_ID_CLAIM, sessionId)
        .issuedAt(now)
        .claim(ISSUED_AT_MICROS_CLAIM, microsRoundedUp(now));
  }

  private static String newSessionId() {
    byte[] raw = new byte[IdMasker.SESSION_ID_BYTES];
    RANDOM.nextBytes(raw);
    return SESSION_ID_ENCODER.encodeToString(raw);
  }

  private static long microsRoundedUp(Instant instant) {
    Instant down = instant.truncatedTo(ChronoUnit.MICROS);
    long micros = ChronoUnit.MICROS.between(Instant.EPOCH, down);
    return down.equals(instant) ? micros : micros + 1;
  }

  private String encode(JwtClaimsSet claims) {
    return encoder
        .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
        .getTokenValue();
  }
}
