package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.auth.support;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Component;

/**
 * Builds the session cookie and the one that ends it (QP-SPRMONO-SES-01, BE-SPR-SEC-03): always
 * HttpOnly, Secure and {@code Path=/}; SameSite and Max-Age come from {@link
 * SessionCookieProperties}. The CSRF cookie lifecycle delegates to Spring and keeps that separate
 * cookie readable by Angular.
 */
@Component
public class SessionCookies {

  private static final String NAME = "sessionToken";

  private final SessionCookieProperties properties;

  private final CsrfTokenRepository csrfTokens;

  SessionCookies(SessionCookieProperties properties, CsrfTokenRepository csrfTokens) {
    this.csrfTokens = csrfTokens;
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

  /** Appends the JWT without replacing the CSRF cookie already written to the response. */
  public void writeSession(String token, HttpServletResponse response) {
    response.addHeader(HttpHeaders.SET_COOKIE, session(token).toString());
  }

  /** Appends the expired JWT cookie alongside the expired CSRF cookie. */
  public void clearSession(HttpServletResponse response) {
    response.addHeader(HttpHeaders.SET_COOKIE, expired().toString());
  }

  /** Materializes and persists the framework token without rotating an existing cookie. */
  public void ensureCsrf(HttpServletRequest request, HttpServletResponse response) {
    Object token = request.getAttribute(CsrfToken.class.getName());
    if (token instanceof CsrfToken current) {
      var _ = current.getToken();
    } else {
      var _ = csrfTokens.loadDeferredToken(request, response).get();
    }
  }

  /** Our login/activation/password controllers call this only after their use case succeeds. */
  public void rotateCsrf(HttpServletRequest request, HttpServletResponse response) {
    csrfTokens.saveToken(csrfTokens.generateToken(request), request, response);
  }

  /** Ends the CSRF cookie with the same attributes it was created with. */
  public void clearCsrf(HttpServletRequest request, HttpServletResponse response) {
    csrfTokens.saveToken(null, request, response);
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
