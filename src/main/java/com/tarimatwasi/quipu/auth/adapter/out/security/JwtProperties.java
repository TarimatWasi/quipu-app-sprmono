package com.tarimatwasi.quipu.auth.adapter.out.security;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The session token ({@code app.jwt.*}).
 *
 * @param secret {@code app.jwt.secret}: from the environment, never from a file; HS256 needs at
 *     least 256 bits
 * @param expiration {@code app.jwt.expiration}: how long a session lasts; the cookie lives as long
 *     (QP-SPRMONO-SES-02, 30 days)
 */
@ConfigurationProperties("app.jwt")
@Validated
public record JwtProperties(@NotBlank String secret, Duration expiration) {

  /** A token that expires at once would log everybody out: fail at startup. */
  public JwtProperties {
    if (expiration == null || expiration.isNegative() || expiration.isZero()) {
      throw new IllegalArgumentException(
          "app.jwt.expiration is required and must be positive, got: " + expiration);
    }
  }
}
