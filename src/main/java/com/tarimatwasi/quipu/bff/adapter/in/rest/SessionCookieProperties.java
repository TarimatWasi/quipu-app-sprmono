package com.tarimatwasi.quipu.bff.adapter.in.rest;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Attributes of the session cookie that are not fixed (Secure, HttpOnly and Path always are).
 *
 * @param sameSite {@code app.session.same-site}: Lax by default (frontend and BFF share the origin
 *     through the hosting rewrite, TAR-75); a product with the frontend on another registrable
 *     domain chooses {@code none}
 * @param maxAge {@code app.session.max-age}, required: the base configuration derives it from the
 *     token lifetime ({@code app.jwt.expiration}) so that cookie and token expire together, and it
 *     has no default of its own that could diverge from the token
 */
@ConfigurationProperties("app.session")
@Validated
public record SessionCookieProperties(@DefaultValue("lax") SameSite sameSite, Duration maxAge) {

  /** A cookie that expires at once (or is deleted) would log everybody out: fail at startup. */
  public SessionCookieProperties {
    if (maxAge == null || maxAge.compareTo(Duration.ofMinutes(1)) < 0) {
      throw new IllegalArgumentException(
          "app.session.max-age is required and must be at least 1 minute (the base configuration"
              + " derives it from app.jwt.expiration), got: "
              + maxAge);
    }
  }

  /** Values of the SameSite attribute. */
  public enum SameSite {
    STRICT("Strict"),
    LAX("Lax"),
    NONE("None");

    private final String attribute;

    SameSite(String attribute) {
      this.attribute = attribute;
    }

    String attribute() {
      return attribute;
    }
  }
}
