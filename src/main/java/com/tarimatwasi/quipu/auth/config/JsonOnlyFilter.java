package com.tarimatwasi.quipu.auth.config;

import com.tarimatwasi.quipu.shared.web.UploadRoutes;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
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
 */
final class JsonOnlyFilter extends OncePerRequestFilter {

  private static final Set<String> STATE_CHANGING = Set.of("POST", "PUT", "PATCH", "DELETE");

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
    if (STATE_CHANGING.contains(request.getMethod())
        && (hasBody(request) || request.getContentType() != null)
        && !isJson(request.getContentType())
        && !isFileUpload(request)) {
      // Write the response directly: sendError triggers an ERROR dispatch to /error, which the
      // security chain would answer with 401 for an unauthenticated client.
      response.setStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value());
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response.getWriter().write(BODY);
      return;
    }
    if (isFileUpload(request) && !declaresItsOrigin(request)) {
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

  /** A multipart POST to one of the exact routes that take a file (ADR-006). */
  private static boolean isFileUpload(HttpServletRequest request) {
    String contentType = request.getContentType();
    if (!"POST".equals(request.getMethod())
        || !UploadRoutes.ALL.contains(request.getRequestURI())
        || contentType == null) {
      return false;
    }
    try {
      return MediaType.MULTIPART_FORM_DATA.equalsTypeAndSubtype(
          MediaType.parseMediaType(contentType));
    } catch (InvalidMediaTypeException e) {
      return false;
    }
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
