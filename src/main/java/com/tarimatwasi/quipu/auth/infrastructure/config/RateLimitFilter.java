package com.tarimatwasi.quipu.auth.infrastructure.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * SEC-01: at most {@code perMinute} requests per minute and client on the login and password
 * recovery endpoints (the path is matched after decoding, so {@code %6Cogin} does not escape it);
 * the next one gets 429 with {@code Retry-After}. It complements the lockout by failed attempts
 * (SEG-06).
 *
 * <p>The counters are a {@link RateLimitBuckets}: in memory and bounded to the most recent clients.
 */
final class RateLimitFilter extends OncePerRequestFilter {

  private static final Set<String> LIMITED =
      Set.of(
          "/bff/auth/login",
          "/bff/auth/activate",
          "/bff/auth/forgot-password",
          "/bff/auth/reset-password");
  private static final Pattern ADDRESS = Pattern.compile("[0-9a-fA-F:.]{1,45}");

  private final RateLimitBuckets buckets;
  private final String clientIpHeader;

  RateLimitFilter(RateLimitProperties properties) {
    this.buckets = new RateLimitBuckets(properties.perMinute());
    this.clientIpHeader = properties.clientIpHeader();
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!"POST".equals(request.getMethod())
        || !LIMITED.contains(decodedPath(request))
        || buckets.tryConsume(clientOf(request), response)) {
      chain.doFilter(request, response);
    }
  }

  /** Decoded and normalized by the container; split between servlet path and path info. */
  private static String decodedPath(HttpServletRequest request) {
    String pathInfo = request.getPathInfo();
    return request.getServletPath() + (pathInfo == null ? "" : pathInfo);
  }

  /** The configured header (first address of a list) or, without it, the connection's address. */
  private String clientOf(HttpServletRequest request) {
    if (!clientIpHeader.isEmpty()) {
      String value = request.getHeader(clientIpHeader);
      if (value != null && !value.isBlank()) {
        int comma = value.indexOf(',');
        String first = (comma < 0 ? value : value.substring(0, comma)).strip();
        // Only something that looks like an address becomes a key: a free-text header would let
        // anybody fill the table with long, distinct keys.
        if (ADDRESS.matcher(first).matches()) {
          return first;
        }
      }
    }
    return request.getRemoteAddr();
  }
}
