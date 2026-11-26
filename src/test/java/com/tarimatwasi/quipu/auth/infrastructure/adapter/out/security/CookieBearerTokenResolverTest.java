package com.tarimatwasi.quipu.auth.infrastructure.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

class CookieBearerTokenResolverTest {

  private final CookieBearerTokenResolver resolver =
      new CookieBearerTokenResolver(
          PathPatternRequestMatcher.withDefaults().matcher("/bff/auth/**"));

  @Test
  void theTokenIsTheValueOfItsCookie() {
    var request = new MockHttpServletRequest("GET", "/bff/admin/payments");
    request.setCookies(new Cookie("other", "x"), new Cookie("sessionToken", "TOKEN"));

    assertThat(resolver.resolve(request)).isEqualTo("TOKEN");
  }

  /** No cookies, or an empty one, is no token: never a 401 for an empty value. */
  @Test
  void noCookieOrAnEmptyOneIsNoToken() {
    var none = new MockHttpServletRequest("GET", "/bff/admin/payments");
    var empty = new MockHttpServletRequest("GET", "/bff/admin/payments");
    empty.setCookies(new Cookie("sessionToken", " "));

    assertThat(resolver.resolve(none)).isNull();
    assertThat(resolver.resolve(empty)).isNull();
  }

  /** A stale cookie must not stop someone from logging in: the public routes read nothing. */
  @Test
  void thePublicRoutesReadNoToken() {
    var login = new MockHttpServletRequest("POST", "/bff/auth/login");
    login.setCookies(new Cookie("sessionToken", "STALE"));

    assertThat(resolver.resolve(login)).isNull();
  }
}
