package com.tarimatwasi.quipu.bff.adapter.in.rest;

import com.tarimatwasi.quipu.auth.application.AccountDisabledException;
import com.tarimatwasi.quipu.auth.application.InvalidCredentialsException;
import com.tarimatwasi.quipu.auth.port.in.AccountLockedException;
import com.tarimatwasi.quipu.auth.port.in.InvalidResetCodeException;
import com.tarimatwasi.quipu.auth.port.in.NoActiveSessionException;
import com.tarimatwasi.quipu.auth.port.in.PasswordUnchangedException;
import com.tarimatwasi.quipu.auth.port.in.WeakPasswordException;
import com.tarimatwasi.quipu.environment.port.in.EmptyEnvironmentUpdateException;
import com.tarimatwasi.quipu.environment.port.in.EnvironmentCodeTakenException;
import com.tarimatwasi.quipu.environment.port.in.EnvironmentNotFoundException;
import com.tarimatwasi.quipu.environment.port.in.InvalidEnvironmentCodeException;
import com.tarimatwasi.quipu.expense.port.in.ExpenseNotFoundException;
import com.tarimatwasi.quipu.expense.port.in.InvalidExpenseException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

// Before Boot's ProblemDetails advice: the BFF answers {code, message, field?}, not problem+json.
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.tarimatwasi.quipu.bff")
public class BffExceptionHandler {

  @ExceptionHandler(InvalidCredentialsException.class)
  public ResponseEntity<BffErrorResponse> handleInvalidCredentials() {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .body(
            new BffErrorResponse("AUTH_INVALID_CREDENTIALS", "Documento o contraseña incorrectos"));
  }

  /** SEG-06: too many failed logins; the lock lifts by itself or with a password reset. */
  @ExceptionHandler(AccountLockedException.class)
  public ResponseEntity<BffErrorResponse> handleAccountLocked() {
    return ResponseEntity.status(HttpStatus.LOCKED)
        .body(
            new BffErrorResponse(
                "AUTH_ACCOUNT_LOCKED",
                "Cuenta bloqueada temporalmente por intentos fallidos. Inténtalo en 15 minutos o"
                    + " recupera tu contraseña"));
  }

  /** Same body as the security entry point: the session is gone, whatever the reason. */
  @ExceptionHandler(NoActiveSessionException.class)
  public ResponseEntity<BffErrorResponse> handleNoActiveSession() {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .body(new BffErrorResponse("AUTH_NO_SESSION", "Tu sesión no es válida o expiró"));
  }

  @ExceptionHandler(AccountDisabledException.class)
  public ResponseEntity<BffErrorResponse> handleAccountDisabled() {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(new BffErrorResponse("AUTH_ACCOUNT_DISABLED", "Cuenta deshabilitada"));
  }

  /** RNF-08: the message for a short password is the one SRS 17 gives for the field. */
  @ExceptionHandler(WeakPasswordException.class)
  public ResponseEntity<BffErrorResponse> handleWeakPassword(WeakPasswordException e) {
    String message =
        e.reason() == WeakPasswordException.Reason.TOO_SHORT
            ? "Mínimo 8 caracteres"
            : "La contraseña es demasiado larga";
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new BffErrorResponse("AUTH_WEAK_PASSWORD", message, "newPassword"));
  }

  @ExceptionHandler(PasswordUnchangedException.class)
  public ResponseEntity<BffErrorResponse> handlePasswordUnchanged() {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            new BffErrorResponse(
                "AUTH_PASSWORD_UNCHANGED",
                "Elige una contraseña distinta de la temporal",
                "newPassword"));
  }

  /** QP-SPRMONO-BFF-01: stable English code, Spanish message, and the first invalid field. */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<BffErrorResponse> handleInvalidInput(MethodArgumentNotValidException e) {
    String field =
        e.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getField())
            .sorted()
            .findFirst()
            .orElse(null);
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new BffErrorResponse("VALIDATION_ERROR", "Datos de entrada inválidos", field));
  }

  /** RF-01: the code is the visible key of the environment; two cannot share it. */
  @ExceptionHandler(EnvironmentCodeTakenException.class)
  public ResponseEntity<BffErrorResponse> handleEnvironmentCodeTaken() {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new BffErrorResponse(
                "ENVIRONMENT_CODE_TAKEN", "Ya existe un ambiente con ese código", "code"));
  }

  @ExceptionHandler(EnvironmentNotFoundException.class)
  public ResponseEntity<BffErrorResponse> handleEnvironmentNotFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new BffErrorResponse("ENVIRONMENT_NOT_FOUND", "El ambiente no existe"));
  }

  @ExceptionHandler(EmptyEnvironmentUpdateException.class)
  public ResponseEntity<BffErrorResponse> handleEmptyEnvironmentUpdate() {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new BffErrorResponse("VALIDATION_ERROR", "Indica al menos un dato para cambiar"));
  }

  /** A code the schema let through that is empty, invisible or outside the allowed alphabet. */
  @ExceptionHandler(InvalidEnvironmentCodeException.class)
  public ResponseEntity<BffErrorResponse> handleInvalidEnvironmentCode() {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            new BffErrorResponse(
                "VALIDATION_ERROR",
                "El código solo puede tener letras, números, espacios y . _ / -",
                "code"));
  }

  /** A body that is not JSON or has a value the type cannot read, such as an unknown enum. */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<BffErrorResponse> handleUnreadableBody() {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new BffErrorResponse("VALIDATION_ERROR", "Datos de entrada inválidos"));
  }

  @ExceptionHandler(ExpenseNotFoundException.class)
  public ResponseEntity<BffErrorResponse> handleExpenseNotFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new BffErrorResponse("EXPENSE_NOT_FOUND", "El egreso no existe"));
  }

  /** RF-07: the amount is positive and has at most two decimals. */
  @ExceptionHandler(InvalidExpenseException.class)
  public ResponseEntity<BffErrorResponse> handleInvalidExpense() {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            new BffErrorResponse(
                "VALIDATION_ERROR",
                "El monto debe ser mayor que 0 y tener como máximo 2 decimales",
                "amount"));
  }

  /** A required query parameter that is not there. */
  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<BffErrorResponse> handleMissingParameter(
      MissingServletRequestParameterException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            new BffErrorResponse(
                "VALIDATION_ERROR", "Datos de entrada inválidos", e.getParameterName()));
  }

  /** A path or query value of the wrong shape, such as an id that is not a UUID. */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<BffErrorResponse> handleBadParameter(
      MethodArgumentTypeMismatchException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new BffErrorResponse("VALIDATION_ERROR", "Datos de entrada inválidos", e.getName()));
  }

  /** One answer for an unknown, expired or used code and for a disabled account (RF-16). */
  @ExceptionHandler(InvalidResetCodeException.class)
  public ResponseEntity<BffErrorResponse> handleInvalidResetCode() {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            new BffErrorResponse(
                "AUTH_INVALID_OR_EXPIRED_CODE", "Enlace inválido o expirado, solicita uno nuevo"));
  }
}
