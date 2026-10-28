package com.tarimatwasi.quipu.auth.infrastructure.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * TAR-221: limits every request of each session user to the ADMIN area (/bff/admin/**, any method),
 * answered with 429 {@code RATE_LIMITED} and {@code Retry-After}. The counters are a {@link
 * RateLimitBuckets} (in memory and bounded to the most recent users).
 *
 * <p>It runs after the authorization, like {@link UploadRateLimitFilter} and {@link
 * AdministratorRateLimitFilter}: the key is the authenticated user (never an address or a header),
 * and a request without session or of another role gets its 401 or 403 untouched. It runs before
 * those two, so a request that it refuses spends nothing of their allowances; one that it lets
 * through may still be refused by them, whose limits keep holding as they are.
 */
final class AdminAreaRateLimitFilter extends OncePerRequestFilter {

  /** The same pattern that SecurityConfig reserves to ADMIN. */
  private static final RequestMatcher ADMIN_AREA =
      PathPatternRequestMatcher.withDefaults().matcher("/bff/admin/**");

  private final RateLimitBuckets buckets;

  AdminAreaRateLimitFilter(AdminAreaRateLimitProperties properties) {
    this.buckets = new RateLimitBuckets(properties.adminRequestsPerMinute());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Authentication session = SecurityContextHolder.getContext().getAuthentication();
    if (session == null
        || !session.isAuthenticated()
        || session instanceof AnonymousAuthenticationToken
        || !ADMIN_AREA.matches(request)
        || buckets.tryConsume(session.getName(), response)) {
      chain.doFilter(request, response);
    }
  }
}
