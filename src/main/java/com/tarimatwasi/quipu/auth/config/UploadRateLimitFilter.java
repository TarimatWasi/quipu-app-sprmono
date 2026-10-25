package com.tarimatwasi.quipu.auth.config;

import com.tarimatwasi.quipu.shared.web.UploadRoutes;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.stream.Stream;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * TAR-206: at most {@code uploadsPerMinute} uploads per minute and session user, over the six
 * routes that take a file ({@link UploadRoutes}); the next one gets 429 {@code RATE_LIMITED} with
 * {@code Retry-After}. The inspection of a PDF shares one global permit, so one user sending costly
 * files could keep everybody else waiting.
 *
 * <p>It runs after the authorization, so the key is the authenticated user (never an address or a
 * header) and a request without session gets its 401 untouched. It runs before the dispatcher
 * parses the form, so a refused upload is never read nor stored. The counters are a {@link
 * RateLimitBuckets}: in memory and bounded to the most recent users.
 */
final class UploadRateLimitFilter extends OncePerRequestFilter {

  private static final PathPatternRequestMatcher.Builder PATHS =
      PathPatternRequestMatcher.withDefaults();

  /** Each route exactly, by its method: neither a trailing slash nor a sub-path is an upload. */
  private static final RequestMatcher UPLOADS =
      new OrRequestMatcher(
          Stream.concat(
                  UploadRoutes.ALL.stream().map(route -> PATHS.matcher(HttpMethod.POST, route)),
                  Stream.of(PATHS.matcher(HttpMethod.PUT, UploadRoutes.GUEST_DOCUMENT_REPLACEMENT)))
              .toArray(RequestMatcher[]::new));

  private final RateLimitBuckets buckets;

  UploadRateLimitFilter(UploadRateLimitProperties properties) {
    this.buckets = new RateLimitBuckets(properties.uploadsPerMinute());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Authentication session = SecurityContextHolder.getContext().getAuthentication();
    if (session == null
        || !session.isAuthenticated()
        || session instanceof AnonymousAuthenticationToken
        || !UPLOADS.matches(request)
        || buckets.tryConsume(session.getName(), response)) {
      chain.doFilter(request, response);
    }
  }
}
