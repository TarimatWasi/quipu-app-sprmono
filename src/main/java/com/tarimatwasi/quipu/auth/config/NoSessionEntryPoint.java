package com.tarimatwasi.quipu.auth.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * Answers a request without a valid session with 401 and the BFF error body the contract defines
 * ({@code AUTH_NO_SESSION}). The body is written by hand because the security filters run before
 * any controller advice.
 */
final class NoSessionEntryPoint implements AuthenticationEntryPoint {

  private static final String BODY =
      "{\"code\":\"AUTH_NO_SESSION\",\"message\":\"Tu sesión no es válida o expiró\"}";

  @Override
  public void commence(
      HttpServletRequest request,
      HttpServletResponse response,
      AuthenticationException authException)
      throws IOException {
    response.setStatus(HttpStatus.UNAUTHORIZED.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(BODY);
  }
}
