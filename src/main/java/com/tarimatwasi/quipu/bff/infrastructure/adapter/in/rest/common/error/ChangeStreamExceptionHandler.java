package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error;

import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.support.ChangeStreamLimitException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ChangeStreamExceptionHandler {
  @ExceptionHandler(ChangeStreamLimitException.class)
  public ResponseEntity<BffErrorResponse> limited() {
    return ResponseEntity.status(429)
        .contentType(MediaType.APPLICATION_JSON)
        .header(HttpHeaders.RETRY_AFTER, "30")
        .body(
            new BffErrorResponse(
                "RATE_LIMITED", "Demasiadas conexiones. Inténtalo nuevamente.", null));
  }
}
