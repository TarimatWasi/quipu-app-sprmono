package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.auth.support;

import java.time.Duration;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Builds the session cookie and the one that ends it (QP-SPRMONO-SES-01, BE-SPR-SEC-03): always
 * HttpOnly, Secure and {@code Path=/}; SameSite and Max-Age come from {@link
 * SessionCookieProperties}.
 */
@Component
public class SessionCookies {

  private static final String NAME = "sessionToken";

  private final SessionCookieProperties properties;

  SessionCookies(SessionCookieProperties properties) {
    this.properties = properties;
  }

  /** The cookie that carries the session token. */
  public ResponseCookie session(String token) {
    return cookie(token, properties.maxAge());
  }

  /** The cookie that makes the browser drop the session: same path and flags, no value. */
  public ResponseCookie expired() {
    return cookie("", Duration.ZERO);
  }

  private ResponseCookie cookie(String value, Duration maxAge) {
    return ResponseCookie.from(NAME, value)
        .httpOnly(true)
        .secure(true)
        .sameSite(properties.sameSite().attribute())
        .maxAge(maxAge)
        .path("/")
        .build();
  }
}
