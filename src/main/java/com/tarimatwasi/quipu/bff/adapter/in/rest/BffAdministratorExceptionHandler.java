package com.tarimatwasi.quipu.bff.adapter.in.rest;

import com.tarimatwasi.quipu.auth.port.in.AdministratorDocumentTakenException;
import com.tarimatwasi.quipu.auth.port.in.AdministratorEmailTakenException;
import com.tarimatwasi.quipu.auth.port.in.AdministratorLimitReachedException;
import com.tarimatwasi.quipu.auth.port.in.AdministratorNotFoundException;
import com.tarimatwasi.quipu.auth.port.in.AdministratorReauthenticationException;
import com.tarimatwasi.quipu.auth.port.in.AdministratorSelfDeactivationException;
import com.tarimatwasi.quipu.auth.port.in.InvalidAdministratorDocumentException;
import com.tarimatwasi.quipu.auth.port.in.LastActiveAdministratorException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RF-22, TAR-127: the errors of creating, deactivating and reactivating an administrator, apart
 * from {@link BffExceptionHandler} so that it stays within the size of a file. The bodies are
 * generic: neither the id nor any personal data of the accounts.
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

  /** An unknown id and the id of an account that is not ADMIN answer alike. */
  @ExceptionHandler(AdministratorNotFoundException.class)
  public ResponseEntity<BffErrorResponse> handleAdministratorNotFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new BffErrorResponse("ADMIN_NOT_FOUND", "El administrador no existe"));
  }

  @ExceptionHandler(AdministratorSelfDeactivationException.class)
  public ResponseEntity<BffErrorResponse> handleSelfDeactivation() {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new BffErrorResponse(
                "ADMIN_SELF_DEACTIVATION", "No puedes desactivar tu propia cuenta"));
  }

  @ExceptionHandler(LastActiveAdministratorException.class)
  public ResponseEntity<BffErrorResponse> handleLastActiveAdministrator() {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new BffErrorResponse(
                "LAST_ACTIVE_ADMIN", "No puedes desactivar al último administrador activo"));
  }
}
