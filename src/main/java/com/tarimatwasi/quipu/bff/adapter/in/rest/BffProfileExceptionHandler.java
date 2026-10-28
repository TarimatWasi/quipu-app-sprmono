package com.tarimatwasi.quipu.bff.adapter.in.rest;

import com.tarimatwasi.quipu.guest.application.port.in.OwnProfileRefusedException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * CU-20, CU-23: the errors of the edit of the own profile, apart from {@link BffExceptionHandler}
 * so that it stays within the size of a file. The bodies are the ones that handler gives.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.tarimatwasi.quipu.bff")
public class BffProfileExceptionHandler {

  private final BffExceptionHandler base;

  public BffProfileExceptionHandler(BffExceptionHandler base) {
    this.base = base;
  }

  /** RN-17: a field that may be left out to keep its value cannot be nulled to erase it. */
  @ExceptionHandler(NullFieldException.class)
  public ResponseEntity<BffErrorResponse> handleNullField(NullFieldException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new BffErrorResponse("VALIDATION_ERROR", "Datos de entrada inválidos", e.field()));
  }

  /** RN-09, RN-33: the profile of the other type of guest, or before the onboarding ends. */
  @ExceptionHandler(OwnProfileRefusedException.class)
  public ResponseEntity<BffErrorResponse> handleOwnProfileRefused(OwnProfileRefusedException e) {
    return switch (e.reason()) {
      case OTHER_GUEST_TYPE -> base.handleOtherGuestType();
      case ONBOARDING_REQUIRED -> base.handleOnboardingRequired();
    };
  }
}
