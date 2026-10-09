package com.tarimatwasi.quipu.auth.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Answers a valid session that cannot use the endpoint with 403 and the BFF error body the contract
 * defines: {@code AUTH_PASSWORD_CHANGE_REQUIRED} while the session is pending a password change
 * (RF-12) and {@code AUTH_FORBIDDEN} when its role cannot use the endpoint. Written by hand for the
 * same reason as {@link NoSessionEntryPoint}: the security filters run before any controller
 * advice.
 */
final class ForbiddenHandler implements AccessDeniedHandler {

  private static final String BODY =
      "{\"code\":\"AUTH_FORBIDDEN\",\"message\":\"No tienes permiso para esta acción\"}";

  // The body is written by hand: the security filters run before any controller advice.
  private static final String PASSWORD_CHANGE_REQUIRED_BODY =
      "{\"code\":\"AUTH_PASSWORD_CHANGE_REQUIRED\","
          + "\"message\":\"Debes cambiar tu contraseña para continuar\"}";

  @Override
  public void handle(
      HttpServletRequest request,
      HttpServletResponse response,
      AccessDeniedException accessDeniedException)
      throws IOException {
    response.setStatus(HttpStatus.FORBIDDEN.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    boolean pending =
        authentication != null && PasswordChangePendingMatcher.isPending(authentication);
    response.getWriter().write(pending ? PASSWORD_CHANGE_REQUIRED_BODY : BODY);
  }
}
