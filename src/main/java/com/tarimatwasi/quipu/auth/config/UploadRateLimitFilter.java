package com.tarimatwasi.quipu.auth.config;

import com.tarimatwasi.quipu.shared.web.UploadRoutes;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
 * TAR-206: limits the uploads of each session user over the six routes that take a file ({@link
 * UploadRoutes}). The inspection of a PDF shares one global permit, so one user sending costly
 * files could keep everybody else waiting. Two limits, both answered with 429 {@code RATE_LIMITED}:
 *
 * <ul>
 *   <li>one upload in progress per user: a second one at once gets {@code Retry-After: 1} and
 *       spends no allowance. The set of uploads in progress only holds the users with a request
 *       still running, so it is bounded by the requests the server takes at once;
 *   <li>{@code uploadBurst} uploads in a row, refilled at {@code uploadsPerMinute} per minute, in a
 *       {@link RateLimitBuckets} (in memory and bounded to the most recent users).
 * </ul>
 *
 * <p>It runs after the authorization, so the key is the authenticated user (never an address or a
 * header) and a request without session gets its 401 untouched. It runs before the dispatcher
 * parses the form, so a refused upload is never read nor stored.
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

  /**
   * The upload in progress of each user, with the token of its request: a request releases only its
   * own token, so ending twice (a timeout, then complete) never frees the next upload (TAR-205).
   */
  private final ConcurrentMap<String, Object> inProgress = new ConcurrentHashMap<>();

  UploadRateLimitFilter(UploadRateLimitProperties properties) {
    this.buckets = new RateLimitBuckets(properties.uploadsPerMinute(), properties.uploadBurst());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Authentication session = SecurityContextHolder.getContext().getAuthentication();
    if (session == null
        || !session.isAuthenticated()
        || session instanceof AnonymousAuthenticationToken
        || !UPLOADS.matches(request)) {
      chain.doFilter(request, response);
      return;
    }
    String user = session.getName();
    Object token = new Object();
    if (inProgress.putIfAbsent(user, token) != null) {
      RateLimitBuckets.reject(response, 1);
      return;
    }
    boolean handedToAsync = false;
    try {
      if (buckets.tryConsume(user, response)) {
        chain.doFilter(request, response);
        if (request.isAsyncStarted()) {
          // The container fires the listener once this dispatch has returned, even if the async
          // work already ended: the upload stays in progress until then.
          request.getAsyncContext().addListener(new Release(user, token));
          handedToAsync = true;
        }
      }
    } finally {
      if (!handedToAsync) {
        inProgress.remove(user, token);
      }
    }
  }

  /** Ends the upload in progress of an async request, however it ends. */
  private final class Release implements AsyncListener {

    private final String user;
    private final Object token;

    Release(String user, Object token) {
      this.user = user;
      this.token = token;
    }

    @Override
    public void onComplete(AsyncEvent event) {
      inProgress.remove(user, token);
    }

    @Override
    public void onTimeout(AsyncEvent event) {
      inProgress.remove(user, token);
    }

    @Override
    public void onError(AsyncEvent event) {
      inProgress.remove(user, token);
    }

    /** A new async cycle of the same request drops the listeners: register again. */
    @Override
    public void onStartAsync(AsyncEvent event) {
      event.getAsyncContext().addListener(this);
    }
  }
}
