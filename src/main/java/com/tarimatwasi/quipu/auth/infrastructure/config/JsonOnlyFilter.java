package com.tarimatwasi.quipu.auth.infrastructure.config;

import com.tarimatwasi.quipu.shared.web.UploadRoutes;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * CSRF mitigation of ADR-006 (QP-SPRMONO-CSR-01): a state-changing request that carries a body or a
 * {@code Content-Type} must be {@code application/json}; otherwise it is rejected with 415 before
 * it reaches authorization.
 *
 * <p>A request with neither body nor {@code Content-Type} (a bodyless DELETE or action POST) is let
 * through: a cross-site one still carries an {@code Origin} header, which the exact-origin CORS
 * configuration rejects with 403 before this filter runs.
 *
 * <p><b>ADR-006 justification:</b> for bodyless state-changing requests this filter does nothing,
 * so the defense rests entirely on CORS rejecting a foreign {@code Origin}. Two tests keep that
 * true and must not be deleted: {@code
 * SecurityContractTest.bodyless_cross_site_request_is_stopped_by_cors} and {@code
 * AuthBffControllerTest.crossSiteBodilessPostIsStoppedByCors} (enforced by {@code
 * TestConventionsTest}).
 *
 * <p>A multipart body is taken by the exact upload routes only: with any other route or method,
 * GET, HEAD and OPTIONS included, it is rejected with the same 415 (TAR-205). The upload routes are
 * compared with the raw URI, so a differently encoded one fails closed with 415.
 *
 * <p>The uploads, and the deletion of a document of a guest (JSON, but irreversible, RN-37), must
 * also say where they come from: {@code Origin} or {@code Sec-Fetch-Site: same-origin}, else 403.
 */
final class JsonOnlyFilter extends OncePerRequestFilter {

  private static final Set<String> STATE_CHANGING = Set.of("POST", "PUT", "PATCH", "DELETE");

  private static final PathPatternRequestMatcher.Builder PATHS =
      PathPatternRequestMatcher.withDefaults();

  /** The replacement of a document of a guest: the only upload by PUT (RN-37). */
  private static final RequestMatcher DOCUMENT_REPLACEMENT =
      PATHS.matcher(HttpMethod.PUT, UploadRoutes.GUEST_DOCUMENT_REPLACEMENT);

  /** The deletion of a document of a guest: JSON, under the check of the uploads (RN-37). */
  private static final RequestMatcher DOCUMENT_DELETION =
      PATHS.matcher(HttpMethod.POST, UploadRoutes.GUEST_DOCUMENT_DELETION);

  /** The same body the security layer writes for a refused request. */
  private static final String FORBIDDEN_BODY =
      "{\"code\":\"AUTH_FORBIDDEN\",\"message\":\"No tienes permiso para esta acción\"}";

  /** BFF error shape (QP-SPRMONO-BFF-01): stable English code, Spanish message. */
  private static final String BODY =
      "{\"code\":\"UNSUPPORTED_MEDIA_TYPE\",\"message\":\"El contenido debe ser application/json.\"}";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    boolean upload = isFileUpload(request);
    // TAR-205: only the six uploads may carry a multipart body, whatever the method, so no other
    // route ever parses one to disk.
    boolean strayMultipart = isMultipart(request.getContentType()) && !upload;
    if (strayMultipart
        || (STATE_CHANGING.contains(request.getMethod())
            && (hasBody(request) || request.getContentType() != null)
            && !isJson(request.getContentType())
            && !upload)) {
      // Write the response directly: sendError triggers an ERROR dispatch to /error, which the
      // security chain would answer with 401 for an unauthenticated client.
      response.setStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value());
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response.getWriter().write(BODY);
      return;
    }
    if ((upload || DOCUMENT_DELETION.matches(request)) && !declaresItsOrigin(request)) {
      response.setStatus(HttpStatus.FORBIDDEN.value());
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response.getWriter().write(FORBIDDEN_BODY);
      return;
    }
    chain.doFilter(request, response);
  }

  /**
   * A multipart form is a "simple" cross-site request, so the upload must say where it comes from:
   * a browser always sends {@code Origin} on a POST (CORS has already refused a foreign one) or
   * {@code Sec-Fetch-Site: same-origin}. A request the browser itself marks {@code cross-site} is
   * refused even with an {@code Origin}, and one with neither header is refused.
   */
  private static boolean declaresItsOrigin(HttpServletRequest request) {
    String fetchSite = request.getHeader("Sec-Fetch-Site");
    if ("cross-site".equals(fetchSite)) {
      return false;
    }
    return request.getHeader(HttpHeaders.ORIGIN) != null || "same-origin".equals(fetchSite);
  }

  /**
   * A multipart POST to one of the exact routes that take a file, or a multipart PUT that replaces
   * a document of a guest (ADR-006).
   */
  private static boolean isFileUpload(HttpServletRequest request) {
    String contentType = request.getContentType();
    boolean route =
        ("POST".equals(request.getMethod()) && UploadRoutes.ALL.contains(request.getRequestURI()))
            || DOCUMENT_REPLACEMENT.matches(request);
    if (!route || contentType == null) {
      return false;
    }
    try {
      return MediaType.MULTIPART_FORM_DATA.equalsTypeAndSubtype(
          MediaType.parseMediaType(contentType));
    } catch (InvalidMediaTypeException e) {
      return false;
    }
  }

  /**
   * Any multipart type, read from the raw header: a value that does not even parse still counts, so
   * a malformed one fails closed.
   */
  private static boolean isMultipart(@Nullable String contentType) {
    return contentType != null
        && contentType.strip().toLowerCase(Locale.ROOT).startsWith("multipart/");
  }

  private static boolean hasBody(HttpServletRequest request) {
    return request.getContentLengthLong() > 0
        || request.getHeader(HttpHeaders.TRANSFER_ENCODING) != null;
  }

  private static boolean isJson(String contentType) {
    if (contentType == null) {
      return false;
    }
    try {
      // equalsTypeAndSubtype: parameters such as charset are allowed
      return MediaType.APPLICATION_JSON.equalsTypeAndSubtype(MediaType.parseMediaType(contentType));
    } catch (InvalidMediaTypeException e) {
      return false;
    }
  }
}
