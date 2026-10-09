package com.tarimatwasi.quipu.auth.config;

import com.tarimatwasi.quipu.auth.adapter.out.security.AccountJwtAuthenticationConverter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Matches what a session pending a password change (RF-12) may NOT reach: everything except
 * changing the password, reading the session and leaving, so the change cannot be skipped by
 * calling the API directly. The rule denies the request; {@link ForbiddenHandler} answers it.
 */
final class PasswordChangePendingMatcher implements RequestMatcher {

  /** What a session pending a password change may still reach. */
  private static final Set<String> ALLOWED_WHILE_PENDING =
      Set.of(
          "/bff/auth/login",
          "/bff/auth/forgot-password",
          "/bff/auth/reset-password",
          "/bff/auth/change-password",
          "/bff/auth/me",
          "/bff/auth/logout",
          // The error dispatch of the container: a failure must show its own body, not this rule.
          "/error");

  /** True if the session in the security context is pending a password change. */
  static boolean isPending(Authentication authentication) {
    return authentication.getAuthorities().stream()
        .anyMatch(
            a ->
                AccountJwtAuthenticationConverter.PASSWORD_CHANGE_PENDING.equals(a.getAuthority()));
  }

  @Override
  public boolean matches(HttpServletRequest request) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    return authentication != null
        && isPending(authentication)
        && !ALLOWED_WHILE_PENDING.contains(pathOf(request));
  }

  private static String pathOf(HttpServletRequest request) {
    return request.getRequestURI().substring(request.getContextPath().length());
  }
}
