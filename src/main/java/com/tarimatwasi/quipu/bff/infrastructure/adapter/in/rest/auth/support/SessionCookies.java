package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.auth.support;

import com.tarimatwasi.quipu.auth.application.port.in.IssuedSession;
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
 *
 * <p>TAR-228: the refresh token travels in its own cookie, HttpOnly and Secure too, with {@code
 * Path=/bff/auth} so the browser sends it only to the refresh and the logout, and with the Max-Age
 * the session gives it (the idle week, never past the 30 days).
 */
@Component
public class SessionCookies {

  /** The cookie of the access token. */
  public static final String SESSION_NAME = "sessionToken";

  /** TAR-228: the cookie of the refresh token. */
  public static final String REFRESH_NAME = "refreshToken";

  private static final String REFRESH_PATH = "/bff/auth";

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

  /**
   * TAR-228: appends the access token and, if the session has one, its refresh token, without
   * replacing the CSRF cookie already written to the response.
   */
  public void writeSession(IssuedSession session, HttpServletResponse response) {
    writeSession(session.accessToken(), response);
    String refreshToken = session.refreshToken();
    Duration maxAge = session.refreshMaxAge();
    if (refreshToken != null && maxAge != null) {
      response.addHeader(
          HttpHeaders.SET_COOKIE,
          cookie(REFRESH_NAME, refreshToken, maxAge, REFRESH_PATH).toString());
    }
  }

  /** Appends the expired JWT and refresh cookies alongside the expired CSRF cookie. */
  public void clearSession(HttpServletResponse response) {
    response.addHeader(HttpHeaders.SET_COOKIE, expired().toString());
    response.addHeader(
        HttpHeaders.SET_COOKIE, cookie(REFRESH_NAME, "", Duration.ZERO, REFRESH_PATH).toString());
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
    return cookie(SESSION_NAME, value, maxAge, "/");
  }

  private ResponseCookie cookie(String name, String value, Duration maxAge, String path) {
    return ResponseCookie.from(name, value)
        .httpOnly(true)
        .secure(true)
        .sameSite(properties.sameSite().attribute())
        .maxAge(maxAge)
        .path(path)
        .build();
  }
}
