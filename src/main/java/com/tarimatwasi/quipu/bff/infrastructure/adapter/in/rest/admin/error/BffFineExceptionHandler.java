package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.admin.error;

import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error.BffErrorResponse;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error.BffExceptionHandler;
import com.tarimatwasi.quipu.fine.application.port.in.FineGuestNotFoundException;
import com.tarimatwasi.quipu.fine.application.port.in.FineNotFoundException;
import com.tarimatwasi.quipu.fine.application.port.in.InvalidFineDescriptionException;
import com.tarimatwasi.quipu.fine.application.port.in.InvalidFineException;
import com.tarimatwasi.quipu.fine.application.port.in.InvalidFineMonthException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RF-19: the errors of the fines, apart from {@link BffExceptionHandler} so that it stays within
 * the size of a file. Both 404 use {@code RESOURCE_NOT_FOUND}, as the contract says, and name no
 * guest.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.tarimatwasi.quipu.bff")
public class BffFineExceptionHandler {

  /** The fine does not exist; a forged fine id gets the same answer. */
  public static ResponseEntity<BffErrorResponse> fineNotFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new BffErrorResponse("RESOURCE_NOT_FOUND", "La multa no existe"));
  }

  @ExceptionHandler(FineNotFoundException.class)
  public ResponseEntity<BffErrorResponse> handleFineNotFound() {
    return fineNotFound();
  }

  /** The guest of a new fine does not exist, or its id was never issued. */
  @ExceptionHandler(FineGuestNotFoundException.class)
  public ResponseEntity<BffErrorResponse> handleFineGuestNotFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new BffErrorResponse("RESOURCE_NOT_FOUND", "El huésped no existe"));
  }

  /** The amount is positive, has at most two decimals and fits the column. */
  @ExceptionHandler(InvalidFineException.class)
  public ResponseEntity<BffErrorResponse> handleInvalidFine() {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            new BffErrorResponse(
                "VALIDATION_ERROR",
                "El monto debe ser mayor que 0 y tener como máximo 2 decimales",
                "amount"));
  }

  /** A month of the pattern that the base does not keep as it is, such as year 0000. */
  @ExceptionHandler(InvalidFineMonthException.class)
  public ResponseEntity<BffErrorResponse> handleInvalidFineMonth() {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new BffErrorResponse("VALIDATION_ERROR", "Datos de entrada inválidos", "month"));
  }

  /** The description is longer than 500 characters. */
  @ExceptionHandler(InvalidFineDescriptionException.class)
  public ResponseEntity<BffErrorResponse> handleInvalidFineDescription() {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            new BffErrorResponse(
                "VALIDATION_ERROR",
                "La descripción admite como máximo 500 caracteres",
                "description"));
  }
}
