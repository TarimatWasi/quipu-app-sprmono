package com.tarimatwasi.quipu.auth.adapter.out.security;

import com.tarimatwasi.quipu.auth.port.out.SessionTokenPort;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
 */
@Component
public class JwtTokenProvider implements SessionTokenPort {

  /**
   * Still written for the clients that read it, but the account decides: the claim is no longer
   * trusted (TAR-125).
   */
  private static final String PASSWORD_CHANGE_PENDING_CLAIM = "mcp";

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

  public String issue(String userId, String role) {
    return issue(userId, role, false);
  }

  @Override
  public String issue(String userId, String role, boolean mustChangePassword) {
    Instant now = clock.instant();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .subject(masker.mask(IdKind.USER, Long.parseLong(userId)).toString())
            .claim("role", role)
            .claim(PASSWORD_CHANGE_PENDING_CLAIM, mustChangePassword)
            .issuedAt(now)
            .expiresAt(now.plus(expiration))
            .build();
    return encoder
        .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
        .getTokenValue();
  }
}
