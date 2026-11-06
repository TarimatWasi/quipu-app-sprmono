package com.tarimatwasi.quipu.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Browser cookie/header fixture for functional tests. Unlike spring-security-test's csrf(), this
 * does not replace the filter's repository globally or add a form parameter to multipart requests.
 * Real bootstrap and lifecycle behavior is verified separately by CsrfContractTest.
 */
public final class BrowserCsrf {

  private static final String COOKIE_NAME = "XSRF-TOKEN";

  private BrowserCsrf() {}

  /** Browser POST with its matching CSRF cookie and header. */
  public static MockHttpServletRequestBuilder postWithCsrf(String path) {
    return post(path).with(csrf());
  }

  /** Supplies the real browser contract, including the header required by OpenAPI. */
  public static RequestPostProcessor csrf() {
    return request -> {
      CsrfToken token = CookieCsrfTokenRepository.withHttpOnlyFalse().generateToken(request);
      List<Cookie> cookies = new ArrayList<>();
      Cookie[] existing = request.getCookies();
      if (existing != null) {
        Arrays.stream(existing)
            .filter(cookie -> !COOKIE_NAME.equals(cookie.getName()))
            .forEach(cookies::add);
      }
      cookies.add(new Cookie(COOKIE_NAME, token.getToken()));
      request.setCookies(cookies.toArray(Cookie[]::new));
      request.addHeader(token.getHeaderName(), token.getToken());
      return request;
    };
  }
}
