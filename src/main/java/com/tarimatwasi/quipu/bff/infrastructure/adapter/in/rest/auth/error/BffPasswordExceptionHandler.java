package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.auth.error;

import com.tarimatwasi.quipu.auth.application.port.in.AdministratorNameRequiredException;
import com.tarimatwasi.quipu.auth.application.port.in.CurrentPasswordMismatchException;
import com.tarimatwasi.quipu.auth.application.port.in.PasswordUnchangedException;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error.BffErrorResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The errors of choosing, changing and recovering a password (RF-12, RF-16, A0.5, A0.6; TAR-127),
 * apart from BffExceptionHandler so that it stays within the size of a file (BE-SPR-COD-09).
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.tarimatwasi.quipu.bff")
public class BffPasswordExceptionHandler {

  /** TAR-127 (A0.6): out of the first access there is no temporary password to name. */
  @ExceptionHandler(PasswordUnchangedException.class)
  public ResponseEntity<BffErrorResponse> handlePasswordUnchanged(PasswordUnchangedException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            new BffErrorResponse(
                "AUTH_PASSWORD_UNCHANGED",
                e.firstAccess()
                    ? "Elige una contraseña distinta de la temporal"
                    : "Elige una contraseña distinta de la actual",
                "newPassword"));
  }

  /**
   * TAR-127 (A0.5): an administrator without name activates its account without writing it. The
   * code is not consumed, so the screen asks for the name and sends it again.
   */
  @ExceptionHandler(AdministratorNameRequiredException.class)
  public ResponseEntity<BffErrorResponse> handleAdministratorNameRequired() {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new BffErrorResponse("VALIDATION_ERROR", "Escribe tu nombre completo", "name"));
  }

  /**
   * TAR-127 (A0.6): 403, not the 401 of the login. The session is valid; the confirmation of the
   * current password is what failed, as in the re-authentication of the administrators.
   */
  @ExceptionHandler(CurrentPasswordMismatchException.class)
  public ResponseEntity<BffErrorResponse> handleCurrentPasswordMismatch() {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(
            new BffErrorResponse(
                "AUTH_INVALID_CREDENTIALS",
                "Tu contraseña actual no es correcta",
                "currentPassword"));
  }
}
