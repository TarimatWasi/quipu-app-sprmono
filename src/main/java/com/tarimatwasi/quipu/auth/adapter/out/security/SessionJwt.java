package com.tarimatwasi.quipu.auth.adapter.out.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * The session token as Spring Security builds it: HS256 with the secret of {@code app.jwt.secret}.
 * The decoder takes no margin around the expiration (the sessions are measured with the injected
 * clock) and refuses a token that has none.
 */
public final class SessionJwt {

  /** HS256 needs a key of at least 256 bits. */
  private static final int MIN_SECRET_BYTES = 32;

  private SessionJwt() {}

  /** The signing key derived from the configured secret. */
  public static SecretKey key(String secret) {
    byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
    if (bytes.length < MIN_SECRET_BYTES) {
      throw new IllegalArgumentException(
          "app.jwt.secret must have at least " + MIN_SECRET_BYTES + " bytes for HS256");
    }
    return new SecretKeySpec(bytes, "HmacSHA256");
  }

  /** Checks the signature and the dates, as of the given clock. */
  public static JwtDecoder decoder(SecretKey key, Clock clock) {
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    var timestamps = new JwtTimestampValidator(Duration.ZERO);
    timestamps.setClock(clock);
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            timestamps, new JwtClaimValidator<Object>(JwtClaimNames.EXP, Objects::nonNull)));
    return decoder;
  }

  /** Signs the claims of a new session token. */
  public static JwtEncoder encoder(SecretKey key) {
    return new NimbusJwtEncoder(new ImmutableSecret<>(key));
  }
}
