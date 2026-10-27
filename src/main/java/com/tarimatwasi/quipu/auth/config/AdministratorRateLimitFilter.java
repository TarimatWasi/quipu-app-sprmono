package com.tarimatwasi.quipu.auth.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * RF-22: limits the creations of administrators of each session user, answered with 429 {@code
 * RATE_LIMITED} and {@code Retry-After}. The counters are a {@link RateLimitBuckets} (in memory and
 * bounded to the most recent users).
 *
 * <p>It runs after the authorization, like {@link UploadRateLimitFilter}: the key is the
 * authenticated user (never an address or a header), and a request without session or of another
 * role gets its 401 or 403 untouched.
 */
final class AdministratorRateLimitFilter extends OncePerRequestFilter {

  /** The route exactly, by its method: neither a trailing slash nor a sub-path is a creation. */
  private static final RequestMatcher CREATION =
      PathPatternRequestMatcher.withDefaults()
          .matcher(HttpMethod.POST, "/bff/admin/administrators");

  private final RateLimitBuckets buckets;

  AdministratorRateLimitFilter(AdministratorRateLimitProperties properties) {
    this.buckets = new RateLimitBuckets(properties.administratorCreationsPerMinute());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Authentication session = SecurityContextHolder.getContext().getAuthentication();
    if (session == null
        || !session.isAuthenticated()
        || session instanceof AnonymousAuthenticationToken
        || !CREATION.matches(request)
        || buckets.tryConsume(session.getName(), response)) {
      chain.doFilter(request, response);
    }
  }
}
