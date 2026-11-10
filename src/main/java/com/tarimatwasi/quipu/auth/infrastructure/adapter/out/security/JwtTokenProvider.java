package com.tarimatwasi.quipu.auth.infrastructure.adapter.out.security;

import com.tarimatwasi.quipu.auth.application.port.out.SessionTokenPort;
import com.tarimatwasi.quipu.auth.application.port.out.UserRepositoryPort;
import com.tarimatwasi.quipu.shared.access.AccessSessions;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/**
 * Issues the session token. Reading it is Spring Security's job ({@link SessionJwt#decoder} and
 * {@link AccountJwtAuthenticationConverter}). The subject is the masked id of the user: the browser
 * can read the token and must not see the sequential id.
 *
 * <p>TAR-227: every token names its session ({@link AccessSessions#SESSION_ID_CLAIM}), 128 bits
 * from {@link SecureRandom} chosen when the session opens and kept when it is renewed. The subject
 * is masked with the key of that session, given here by its id: the token is being issued, so no
 * request carries the session yet.
 *
 * <p>TAR-228: the session is stored and renewed with a refresh token ({@code SessionService}); the
 * id is its rotation family. Every token is marked with {@link #STORED_SESSION_CLAIM}, and the
 * tokens issued before sessions were stored lack it and are refused.
 */
@Component
public class JwtTokenProvider implements SessionTokenPort {

  static final String SECURITY_VERSION_CLAIM = "sv";

  /** TAR-228: the token belongs to a stored session, revocable on the server. */
  static final String STORED_SESSION_CLAIM = "ss";

  private final UserRepositoryPort users;

  /** Thread-safe; one for the whole process, so the sessions draw from one seeded source. */
  private static final SecureRandom RANDOM = new SecureRandom();

  private static final Base64.Encoder SESSION_ID_ENCODER = Base64.getUrlEncoder().withoutPadding();

  private final JwtEncoder encoder;
  private final Duration expiration;
  private final Clock clock;
  private final IdMasker masker;
  private final JwtDecoder decoder;

  public JwtTokenProvider(
      JwtProperties properties,
      Clock clock,
      JwtEncoder encoder,
      JwtDecoder decoder,
      IdMasker masker,
      UserRepositoryPort users) {
    this.users = users;
    this.encoder = encoder;
    this.decoder = decoder;
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
  public String issueTemporary(String userId, String sessionId, Long grantId, Instant expiresAt) {
    Instant now = clock.instant();
    return encode(
        builder(userId, sessionId, now)
            .expiresAt(expiresAt)
            .claim(AccessSessions.TEMPORARY_CLAIM, grantId.toString())
            .build());
  }

  private JwtClaimsSet.Builder builder(String userId, String sessionId, Instant now) {
    return JwtClaimsSet.builder()
        .claim(SECURITY_VERSION_CLAIM, users.securityVersion(Long.parseLong(userId)))
        .subject(masker.session(sessionId).mask(IdKind.USER, Long.parseLong(userId)).toString())
        .claim(AccessSessions.SESSION_ID_CLAIM, sessionId)
        .claim(STORED_SESSION_CLAIM, true)
        .issuedAt(now);
  }

  @Override
  public Optional<String> sessionIdOf(String accessToken) {
    try {
      return Optional.ofNullable(
          decoder.decode(accessToken).getClaimAsString(AccessSessions.SESSION_ID_CLAIM));
    } catch (JwtException notOurs) {
      return Optional.empty();
    }
  }

  private static String newSessionId() {
    byte[] raw = new byte[IdMasker.SESSION_ID_BYTES];
    RANDOM.nextBytes(raw);
    return SESSION_ID_ENCODER.encodeToString(raw);
  }

  private String encode(JwtClaimsSet claims) {
    return encoder
        .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
        .getTokenValue();
  }
}
