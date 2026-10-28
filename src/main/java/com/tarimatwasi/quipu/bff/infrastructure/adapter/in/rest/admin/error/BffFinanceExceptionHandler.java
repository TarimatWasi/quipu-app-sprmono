package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.admin.error;

import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error.BffErrorResponse;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error.BffExceptionHandler;
import com.tarimatwasi.quipu.payment.application.port.in.InvalidFinanceMonthException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RF-18: the errors of the balance of a month, apart from {@link BffExceptionHandler} so that it
 * stays within the size of a file.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.tarimatwasi.quipu.bff")
public class BffFinanceExceptionHandler {

  /** A month of the pattern that the base does not keep as it is, such as year 0000. */
  @ExceptionHandler(InvalidFinanceMonthException.class)
  public ResponseEntity<BffErrorResponse> handleInvalidFinanceMonth() {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new BffErrorResponse("VALIDATION_ERROR", "Datos de entrada inválidos", "month"));
  }
}
