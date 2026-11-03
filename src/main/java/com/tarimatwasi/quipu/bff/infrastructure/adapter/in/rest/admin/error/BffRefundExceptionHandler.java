package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.admin.error;

import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error.BffErrorResponse;
import com.tarimatwasi.quipu.payment.application.port.in.InvalidRefundException;
import com.tarimatwasi.quipu.payment.application.port.in.RefundGuestNotFoundException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** RF-24, TAR-240: the errors of the refunds, kept apart as those of the fines are. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.tarimatwasi.quipu.bff")
public class BffRefundExceptionHandler {

  /** The guest of a new refund does not exist, or its id was never issued. */
  @ExceptionHandler(RefundGuestNotFoundException.class)
  public ResponseEntity<BffErrorResponse> handleRefundGuestNotFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new BffErrorResponse("RESOURCE_NOT_FOUND", "El huésped no existe"));
  }

  /** A field out of range, or an amount over the pending credit of the guest (RN-38). */
  @ExceptionHandler(InvalidRefundException.class)
  public ResponseEntity<BffErrorResponse> handleInvalidRefund(InvalidRefundException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            new BffErrorResponse(
                "VALIDATION_ERROR", "Los datos de la devolución no son válidos", e.field()));
  }
}
