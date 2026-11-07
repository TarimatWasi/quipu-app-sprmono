package com.tarimatwasi.quipu.auth.infrastructure.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * TAR-144, TAR-9: only what Vercel forwards reaches {@code /bff/**}. Vercel adds {@value
 * #SECRET_HEADER} to the requests it rewrites to the backend, replacing whatever the visitor sent;
 * a request without it, or with another value, is answered with 403 before any other filter (CORS,
 * rate limits, session) reads it. That closes the direct URL of the hosting provider to the API.
 *
 * <p>Once the secret is verified the request carries a trustworthy {@value #VERCEL_ADDRESS_HEADER}
 * (Vercel writes it with the address of the browser), so the address limits key on it ({@link
 * #CLIENT_ADDRESS}); a client cannot choose its bucket, because without the secret it never gets
 * past this filter. {@code /actuator/health} and everything outside {@code /bff/} stay open: the
 * hosting provider and the deploy workflow check the health directly.
 *
 * <p>The secrets are compared in constant time, through their digests, so neither the value nor its
 * length leaks by timing.
 */
final class EdgeGateFilter extends OncePerRequestFilter {

  /** The header Vercel sets with the shared secret. */
  static final String SECRET_HEADER = "X-Quipu-Edge-Secret";

  /** The header Vercel sets with the address of the browser. */
  static final String VERCEL_ADDRESS_HEADER = "X-Vercel-Forwarded-For";

  /** Request attribute: the address of the browser, set only after the secret was verified. */
  static final String CLIENT_ADDRESS = EdgeGateFilter.class.getName() + ".CLIENT_ADDRESS";

  private static final Pattern ADDRESS = Pattern.compile("[0-9a-fA-F:.]{1,45}");
  private static final String BODY =
      "{\"code\":\"AUTH_FORBIDDEN\",\"message\":\"No tienes permiso para esta acción\"}";

  private final List<byte[]> digests;

  EdgeGateFilter(EdgeProperties properties) {
    this.digests = properties.secrets().stream().map(EdgeGateFilter::digest).toList();
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = decodedPath(request);
    if (!path.equals("/bff") && !path.startsWith("/bff/")) {
      chain.doFilter(request, response);
      return;
    }
    if (!fromTheEdge(request)) {
      response.setStatus(HttpStatus.FORBIDDEN.value());
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response.getWriter().write(BODY);
      return;
    }
    String address = browserAddress(request);
    if (address != null) {
      request.setAttribute(CLIENT_ADDRESS, address);
    }
    chain.doFilter(request, response);
  }

  private boolean fromTheEdge(HttpServletRequest request) {
    String sent = request.getHeader(SECRET_HEADER);
    if (sent == null) {
      return false;
    }
    byte[] sentDigest = digest(sent);
    boolean match = false;
    // Every accepted secret is compared: the time does not tell which one matched.
    for (byte[] accepted : digests) {
      match |= MessageDigest.isEqual(accepted, sentDigest);
    }
    return match;
  }

  /** The first address of the Vercel header, if it looks like one; else null. */
  private static @Nullable String browserAddress(HttpServletRequest request) {
    String value = request.getHeader(VERCEL_ADDRESS_HEADER);
    if (value == null || value.isBlank()) {
      return null;
    }
    int comma = value.indexOf(',');
    String first = (comma < 0 ? value : value.substring(0, comma)).strip();
    return ADDRESS.matcher(first).matches() ? first : null;
  }

  /** Decoded and normalized by the container; split between servlet path and path info. */
  private static String decodedPath(HttpServletRequest request) {
    String pathInfo = request.getPathInfo();
    return request.getServletPath() + (pathInfo == null ? "" : pathInfo);
  }

  private static byte[] digest(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
