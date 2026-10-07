package com.tarimatwasi.quipu.auth.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * SEC-01: at most {@code perMinute} requests per minute and client on the login and password
 * recovery endpoints (the path is matched after decoding, so {@code %6Cogin} does not escape it);
 * the next one gets 429 with {@code Retry-After}. It complements the lockout by failed attempts
 * (SEG-06).
 *
 * <p>The counters live in memory, which is right for the single instance of the free plan. The
 * table keeps the most recent clients only, so it cannot grow without bound: an attacker rotating
 * thousands of addresses can make a real client start over, which only means a fresh allowance.
 */
final class RateLimitFilter extends OncePerRequestFilter {

  private static final Set<String> LIMITED =
      Set.of("/bff/auth/login", "/bff/auth/forgot-password", "/bff/auth/reset-password");
  private static final int MAX_TRACKED_CLIENTS = 10_000;
  private static final Pattern ADDRESS = Pattern.compile("[0-9a-fA-F:.]{1,45}");
  private static final String BODY =
      "{\"code\":\"RATE_LIMITED\",\"message\":\"Demasiados intentos, espera un momento antes de"
          + " volver a intentar\"}";

  private final int perMinute;
  private final String clientIpHeader;
  private final Map<String, Bucket> buckets =
      Collections.synchronizedMap(
          new LinkedHashMap<>(16, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
              return size() > MAX_TRACKED_CLIENTS;
            }
          });

  RateLimitFilter(RateLimitProperties properties) {
    this.perMinute = properties.perMinute();
    this.clientIpHeader = properties.clientIpHeader();
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!"POST".equals(request.getMethod()) || !LIMITED.contains(decodedPath(request))) {
      chain.doFilter(request, response);
      return;
    }
    Bucket bucket = buckets.computeIfAbsent(clientOf(request), key -> newBucket());
    ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
    if (probe.isConsumed()) {
      chain.doFilter(request, response);
      return;
    }
    long retryAfterSeconds =
        Math.max(1, Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds() + 1);
    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(BODY);
  }

  /** Decoded and normalized by the container; split between servlet path and path info. */
  private static String decodedPath(HttpServletRequest request) {
    String pathInfo = request.getPathInfo();
    return request.getServletPath() + (pathInfo == null ? "" : pathInfo);
  }

  private Bucket newBucket() {
    return Bucket.builder()
        .addLimit(
            Bandwidth.builder()
                .capacity(perMinute)
                .refillGreedy(perMinute, Duration.ofMinutes(1))
                .build())
        .build();
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
