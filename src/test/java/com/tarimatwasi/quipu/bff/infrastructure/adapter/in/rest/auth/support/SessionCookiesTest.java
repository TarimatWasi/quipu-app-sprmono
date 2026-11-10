package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.auth.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.auth.application.port.in.IssuedSession;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.auth.support.SessionCookieProperties.SameSite;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

/** QP-SPRMONO-SES-01, BE-SPR-SEC-03 (TAR-173): the attributes of the session cookie. */
class SessionCookiesTest {

  private final SessionCookies cookies =
      new SessionCookies(
          new SessionCookieProperties(SameSite.NONE, Duration.ofDays(30)),
          CookieCsrfTokenRepository.withHttpOnlyFalse());

  @Test
  void session_carriesTheTokenWithTheConfiguredAttributes() {
    ResponseCookie cookie = cookies.session("the-token");

    assertThat(cookie.getName()).isEqualTo("sessionToken");
    assertThat(cookie.getValue()).isEqualTo("the-token");
    assertThat(cookie.isHttpOnly()).isTrue();
    assertThat(cookie.isSecure()).isTrue();
    assertThat(cookie.getSameSite()).isEqualTo("None");
    assertThat(cookie.getPath()).isEqualTo("/");
    assertThat(cookie.getMaxAge()).isEqualTo(Duration.ofDays(30));
  }

  /** TAR-228: a full session writes its access token and its refresh token, for /bff/auth only. */
  @Test
  void writeSession_ofAFullSessionWritesBothCookies() {
    var response = new MockHttpServletResponse();

    cookies.writeSession(new IssuedSession("access", "sid.secret", Duration.ofDays(7)), response);

    assertThat(response.getHeaders(HttpHeaders.SET_COOKIE))
        .anySatisfy(
            value ->
                assertThat(value).startsWith("sessionToken=access").contains("Max-Age=2592000"))
        .anySatisfy(
            value ->
                assertThat(value)
                    .startsWith("refreshToken=sid.secret")
                    .contains("Path=/bff/auth", "Max-Age=604800", "HttpOnly", "Secure"));
  }

  /**
   * TAR-228: a temporary session has no refresh token, so it expires the one the browser may hold
   * (of a session it replaces, maybe of another account), and its access token lasts as long as its
   * temporary password, not the configured lifetime.
   */
  @Test
  void writeSession_ofATemporarySessionExpiresTheRefreshTokenAndLastsAsItsPassword() {
    var response = new MockHttpServletResponse();

    cookies.writeSession(
        new IssuedSession("temporary", null, null, Duration.ofMinutes(30)), response);

    assertThat(response.getHeaders(HttpHeaders.SET_COOKIE))
        .anySatisfy(
            value ->
                assertThat(value).startsWith("sessionToken=temporary").contains("Max-Age=1800"))
        .anySatisfy(
            value ->
                assertThat(value)
                    .startsWith("refreshToken=;")
                    .contains("Path=/bff/auth", "Max-Age=0"));
  }

  @Test
  void expired_keepsTheSameFlagsAndEndsTheCookieAtOnce() {
    ResponseCookie cookie = cookies.expired();

    assertThat(cookie.getName()).isEqualTo("sessionToken");
    assertThat(cookie.getValue()).isEmpty();
    assertThat(cookie.isHttpOnly()).isTrue();
    assertThat(cookie.isSecure()).isTrue();
    assertThat(cookie.getSameSite()).isEqualTo("None");
    assertThat(cookie.getPath()).isEqualTo("/");
    assertThat(cookie.getMaxAge()).isEqualTo(Duration.ZERO);
  }
}
