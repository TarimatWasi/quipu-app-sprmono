package com.tarimatwasi.quipu.auth.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;
import org.springframework.security.web.csrf.MissingCsrfTokenException;

/** TAR-300: CSRF failures remain distinct from authorization, with no token in the response. */
class ForbiddenHandlerTest {

  @AfterEach
  void clearAuthentication() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void anInvalidTokenIsNotReportedAsAPendingPasswordChange() throws Exception {
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(
                "user", "unused", List.of(new SimpleGrantedAuthority("PASSWORD_CHANGE_PENDING"))));
    var response = new MockHttpServletResponse();

    new ForbiddenHandler()
        .handle(
            new MockHttpServletRequest(),
            response,
            new InvalidCsrfTokenException(
                new DefaultCsrfToken("header", "parameter", "expected-secret"),
                "submitted-secret"));

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentType()).startsWith("application/json");
    assertThat(response.getContentAsString())
        .contains("AUTH_CSRF_INVALID")
        .doesNotContain("AUTH_PASSWORD_CHANGE_REQUIRED", "expected-secret", "submitted-secret");
  }

  @Test
  void aMissingTokenUsesTheSameSafeError() throws Exception {
    var response = new MockHttpServletResponse();

    new ForbiddenHandler()
        .handle(
            new MockHttpServletRequest(),
            response,
            new MissingCsrfTokenException("submitted-secret"));

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString())
        .contains("AUTH_CSRF_INVALID")
        .doesNotContain("submitted-secret");
  }
}
