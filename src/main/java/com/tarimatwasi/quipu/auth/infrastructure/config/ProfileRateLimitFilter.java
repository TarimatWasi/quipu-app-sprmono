package com.tarimatwasi.quipu.auth.infrastructure.config;

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
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * CU-20, CU-23: limits the edits of the own profile of each session user, both routes in one
 * counter, answered with 429 {@code RATE_LIMITED} and {@code Retry-After}. The counters are a
 * {@link RateLimitBuckets} (in memory and bounded to the most recent users).
 *
 * <p>It runs after the authorization, like {@link AdministratorRateLimitFilter}: the key is the
 * authenticated user (never an address or a header), and a request without session or of another
 * role gets its 401 or 403 untouched.
 */
final class ProfileRateLimitFilter extends OncePerRequestFilter {

  private static final PathPatternRequestMatcher.Builder PATHS =
      PathPatternRequestMatcher.withDefaults();

  /** Exactly the two routes, by their method; neither a trailing slash nor a sub-path. */
  private static final RequestMatcher PROFILE_ROUTES =
      new OrRequestMatcher(
          PATHS.matcher(HttpMethod.PATCH, "/bff/guest/profile"),
          PATHS.matcher(HttpMethod.PATCH, "/bff/temporary-guest/profile"));

  private final RateLimitBuckets buckets;

  ProfileRateLimitFilter(ProfileRateLimitProperties properties) {
    this.buckets = new RateLimitBuckets(properties.profileUpdatesPerMinute());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Authentication session = SecurityContextHolder.getContext().getAuthentication();
    if (session == null
        || !session.isAuthenticated()
        || session instanceof AnonymousAuthenticationToken
        || !PROFILE_ROUTES.matches(request)
        || buckets.tryConsume(session.getName(), response)) {
      chain.doFilter(request, response);
    }
  }
}
