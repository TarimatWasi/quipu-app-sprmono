package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error;

import com.tarimatwasi.quipu.notification.application.port.in.NotificationNotFoundException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** The bell (v0.27.0): a notification of another reader answers like a missing one. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.tarimatwasi.quipu.bff")
public class BffNotificationExceptionHandler {

  /** Missing, of another reader or a forged id: one answer for all. */
  public static ResponseEntity<BffErrorResponse> notificationNotFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new BffErrorResponse("RESOURCE_NOT_FOUND", "La notificación no existe"));
  }

  @ExceptionHandler(NotificationNotFoundException.class)
  public ResponseEntity<BffErrorResponse> handleNotificationNotFound() {
    return notificationNotFound();
  }
}
