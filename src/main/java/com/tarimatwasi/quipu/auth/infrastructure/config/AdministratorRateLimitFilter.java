package com.tarimatwasi.quipu.auth.infrastructure.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * RF-22 and TAR-127: limits the creations, the deactivations and the reactivations of
 * administrators of each session user, each route with its own counter, answered with 429 {@code
 * RATE_LIMITED} and {@code Retry-After}. The counters are {@link RateLimitBuckets} (in memory and
 * bounded to the most recent users).
 *
 * <p>It runs after the authorization, like {@link UploadRateLimitFilter}: the key is the
 * authenticated user (never an address or a header), and a request without session or of another
 * role gets its 401 or 403 untouched.
 */
final class AdministratorRateLimitFilter extends OncePerRequestFilter {

  /** A route exactly, by its method, and its counters. */
  private record LimitedRoute(RequestMatcher route, RateLimitBuckets buckets) {}

  private final List<LimitedRoute> routes;

  AdministratorRateLimitFilter(AdministratorRateLimitProperties properties) {
    var paths = PathPatternRequestMatcher.withDefaults();
    int statusChanges = properties.administratorStatusChangesPerMinute();
    // Neither a trailing slash nor another sub-path is one of these routes.
    this.routes =
        List.of(
            new LimitedRoute(
                paths.matcher(HttpMethod.POST, "/bff/admin/administrators"),
                new RateLimitBuckets(properties.administratorCreationsPerMinute())),
            new LimitedRoute(
                paths.matcher(HttpMethod.POST, "/bff/admin/administrators/{id}/deactivate"),
                new RateLimitBuckets(statusChanges)),
            new LimitedRoute(
                paths.matcher(HttpMethod.POST, "/bff/admin/administrators/{id}/reactivate"),
                new RateLimitBuckets(statusChanges)));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Authentication session = SecurityContextHolder.getContext().getAuthentication();
    if (session == null
        || !session.isAuthenticated()
        || session instanceof AnonymousAuthenticationToken) {
      chain.doFilter(request, response);
      return;
    }
    for (LimitedRoute limited : routes) {
      if (limited.route().matches(request)) {
        if (limited.buckets().tryConsume(session.getName(), response)) {
          chain.doFilter(request, response);
        }
        return;
      }
    }
    chain.doFilter(request, response);
  }
}
