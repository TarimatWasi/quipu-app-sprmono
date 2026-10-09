package com.tarimatwasi.quipu.auth.adapter.out.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Reads the session token from the {@code sessionToken} cookie. On the public endpoints it reads
 * nothing: Spring's bearer filter answers 401 to an invalid token even where no session is needed,
 * and a stale cookie must not stop someone from logging in or recovering the password.
 */
public final class CookieBearerTokenResolver implements BearerTokenResolver {

  public static final String COOKIE_NAME = "sessionToken";

  private final RequestMatcher publicRequests;

  public CookieBearerTokenResolver(RequestMatcher publicRequests) {
    this.publicRequests = publicRequests;
  }

  @Override
  public @Nullable String resolve(HttpServletRequest request) {
    if (publicRequests.matches(request)) {
      return null;
    }
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return null;
    }
    return Arrays.stream(cookies)
        .filter(cookie -> COOKIE_NAME.equals(cookie.getName()))
        .map(Cookie::getValue)
        .filter(value -> !value.isBlank())
        .findFirst()
        .orElse(null);
  }
}
