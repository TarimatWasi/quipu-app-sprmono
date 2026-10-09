package com.tarimatwasi.quipu.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.auth.adapter.out.security.AccountJwtAuthenticationConverter;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** RF-12: a session pending a password change can only change it, read the session and leave. */
class PasswordChangePendingMatcherTest {

  private final PasswordChangePendingMatcher matcher = new PasswordChangePendingMatcher();

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  private static void signIn(String... authorities) {
    List<GrantedAuthority> granted =
        java.util.Arrays.stream(authorities)
            .<GrantedAuthority>map(SimpleGrantedAuthority::new)
            .toList();
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken("7", null, granted));
  }

  private static MockHttpServletRequest request(String path) {
    var request = new MockHttpServletRequest("GET", path);
    request.setRequestURI(path);
    return request;
  }

  @Test
  void aPendingSessionIsDeniedEverythingExceptTheAllowedPaths() {
    signIn("ROLE_ADMIN", AccountJwtAuthenticationConverter.PASSWORD_CHANGE_PENDING);

    assertThat(matcher.matches(request("/bff/admin/environments"))).isTrue();
    assertThat(matcher.matches(request("/bff/auth/me/"))).isTrue();
    assertThat(matcher.matches(request("/BFF/auth/me"))).isTrue();
    for (String allowed :
        new String[] {
          "/bff/auth/login",
          "/bff/auth/forgot-password",
          "/bff/auth/reset-password",
          "/bff/auth/change-password",
          "/bff/auth/me",
          "/bff/auth/logout",
          "/error"
        }) {
      assertThat(matcher.matches(request(allowed))).as(allowed).isFalse();
    }
  }

  @Test
  void aSessionThatDoesNotMustChangeItsPasswordIsNeverDeniedByThisRule() {
    signIn("ROLE_ADMIN");

    assertThat(matcher.matches(request("/bff/admin/environments"))).isFalse();
  }

  @Test
  void withoutASessionTheRuleDoesNotApply() {
    assertThat(matcher.matches(request("/bff/admin/environments"))).isFalse();
  }

  @Test
  void theContextPathIsNotPartOfTheComparedPath() {
    signIn(AccountJwtAuthenticationConverter.PASSWORD_CHANGE_PENDING);
    var request = new MockHttpServletRequest("GET", "/api/bff/auth/me");
    request.setContextPath("/api");
    request.setRequestURI("/api/bff/auth/me");

    assertThat(matcher.matches(request)).isFalse();
  }
}
