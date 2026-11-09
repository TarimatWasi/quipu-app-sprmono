package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.admin.error;

import com.tarimatwasi.quipu.auth.application.port.in.AccountEmailTakenException;
import com.tarimatwasi.quipu.auth.application.port.in.EmailVerificationException;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error.BffCooldownResponse;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error.BffErrorResponse;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error.BffExceptionHandler;
import com.tarimatwasi.quipu.guest.application.port.in.GuestAccountEmailTakenException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * TAR-229, TAR-349: the errors of the one email of a guest, apart from {@link BffExceptionHandler}
 * so that it stays within the size of a file. The bodies are generic: neither the email nor any
 * other personal data of the accounts.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.tarimatwasi.quipu.bff")
public class BffGuestAccountExceptionHandler {

  private static final String EMAIL = "email";
  private static final String CODE = "code";

  /** The same code and message as for an administrator, whoever set the email. */
  @ExceptionHandler({GuestAccountEmailTakenException.class, AccountEmailTakenException.class})
  public ResponseEntity<BffErrorResponse> handleAccountEmailTaken() {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new BffErrorResponse(
                "ACCOUNT_EMAIL_TAKEN", "Ese correo ya está en uso por otra cuenta", EMAIL));
  }

  /** The wait of a cooldown goes both in Retry-After and in the body (TAR-349). */
  @ExceptionHandler(EmailVerificationException.class)
  public ResponseEntity<?> handleEmailVerification(EmailVerificationException e) {
    return switch (e.reason()) {
      case COOLDOWN ->
          ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
              .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()))
              .body(
                  new BffCooldownResponse(
                      "EMAIL_VERIFICATION_COOLDOWN",
                      "Espera un momento antes de pedir otro código",
                      EMAIL,
                      e.retryAfterSeconds()));
      case UNCHANGED -> badRequest("EMAIL_UNCHANGED", "Ese ya es tu correo", EMAIL);
      case INVALID_CODE ->
          badRequest("EMAIL_VERIFICATION_INVALID", "El código no es correcto", CODE);
      case EXPIRED ->
          badRequest("EMAIL_VERIFICATION_EXPIRED", "El código venció, pide uno nuevo", CODE);
    };
  }

  private static ResponseEntity<BffErrorResponse> badRequest(
      String code, String message, String field) {
    return ResponseEntity.badRequest().body(new BffErrorResponse(code, message, field));
  }
}
