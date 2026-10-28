package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.auth.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.auth.support.SessionCookieProperties.SameSite;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

/** QP-SPRMONO-SES-01, BE-SPR-SEC-03 (TAR-173): the attributes of the session cookie. */
class SessionCookiesTest {

  private final SessionCookies cookies =
      new SessionCookies(new SessionCookieProperties(SameSite.NONE, Duration.ofDays(30)));

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
