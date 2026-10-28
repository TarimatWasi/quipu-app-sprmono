package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.admin.error;

import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error.BffErrorResponse;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error.BffExceptionHandler;
import com.tarimatwasi.quipu.guest.application.port.in.GuestAccountEmailTakenException;
import com.tarimatwasi.quipu.guest.application.port.in.GuestWithoutAccountException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * TAR-229: the errors of the account email of a guest, apart from {@link BffExceptionHandler} so
 * that it stays within the size of a file. The bodies are generic: neither the email nor any other
 * personal data of the accounts.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.tarimatwasi.quipu.bff")
public class BffGuestAccountExceptionHandler {

  /** The same code and message as for an administrator; the field is the input that carried it. */
  @ExceptionHandler(GuestAccountEmailTakenException.class)
  public ResponseEntity<BffErrorResponse> handleAccountEmailTaken(
      GuestAccountEmailTakenException e) {
    String field =
        switch (e.field()) {
          case CONTACT_EMAIL -> "contactEmail";
          case EMAIL -> "email";
        };
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new BffErrorResponse(
                "ACCOUNT_EMAIL_TAKEN", "Ese correo ya pertenece a una cuenta", field));
  }

  @ExceptionHandler(GuestWithoutAccountException.class)
  public ResponseEntity<BffErrorResponse> handleGuestWithoutAccount() {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new BffErrorResponse(
                "GUEST_WITHOUT_ACCOUNT",
                "El huésped aún no tiene cuenta de acceso; genérale un acceso primero"));
  }
}
