package com.tarimatwasi.quipu.bff.adapter.in.rest;

import com.tarimatwasi.quipu.auth.port.in.AdministratorDocumentTakenException;
import com.tarimatwasi.quipu.auth.port.in.AdministratorEmailTakenException;
import com.tarimatwasi.quipu.auth.port.in.AdministratorLimitReachedException;
import com.tarimatwasi.quipu.auth.port.in.AdministratorReauthenticationException;
import com.tarimatwasi.quipu.auth.port.in.InvalidAdministratorDocumentException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RF-22: the errors of creating an administrator, apart from {@link BffExceptionHandler} so that it
 * stays within the size of a file.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.tarimatwasi.quipu.bff")
public class BffAdministratorExceptionHandler {

  /** A number the schema let through that does not fit its type (a DNI has 8 digits). */
  @ExceptionHandler(InvalidAdministratorDocumentException.class)
  public ResponseEntity<BffErrorResponse> handleInvalidDocument() {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            new BffErrorResponse(
                "VALIDATION_ERROR",
                "El número de documento no corresponde a su tipo",
                "documentNumber"));
  }

  /**
   * 403, not the 401 of the login: the session is valid, the confirmation of the password is what
   * failed.
   */
  @ExceptionHandler(AdministratorReauthenticationException.class)
  public ResponseEntity<BffErrorResponse> handleReauthenticationFailed() {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(
            new BffErrorResponse(
                "AUTH_INVALID_CREDENTIALS",
                "Tu contraseña actual no es correcta",
                "currentPassword"));
  }

  @ExceptionHandler(AdministratorDocumentTakenException.class)
  public ResponseEntity<BffErrorResponse> handleDocumentTaken() {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new BffErrorResponse(
                "ACCOUNT_DOCUMENT_TAKEN",
                "Ese documento ya pertenece a una cuenta",
                "documentNumber"));
  }

  @ExceptionHandler(AdministratorEmailTakenException.class)
  public ResponseEntity<BffErrorResponse> handleEmailTaken() {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new BffErrorResponse(
                "ACCOUNT_EMAIL_TAKEN", "Ese correo ya pertenece a una cuenta", "email"));
  }

  @ExceptionHandler(AdministratorLimitReachedException.class)
  public ResponseEntity<BffErrorResponse> handleLimitReached() {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new BffErrorResponse(
                "ADMIN_LIMIT_REACHED", "Ya existe el número máximo de administradores"));
  }
}
