package com.tarimatwasi.quipu.bff.adapter.in.rest;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * The size limit of the multipart form (RF-15) is raised while the servlet reads the request,
 * before any controller has been chosen. An advice limited to the BFF package is skipped for a
 * request that has no handler yet, so this one has no package limit and answers only this error.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class BffUploadExceptionHandler {

  /** The form or its file is over the limit: the same answer as a file that is not a PDF. */
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<BffErrorResponse> handleUploadTooLarge() {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            new BffErrorResponse(
                "CONTRACT_INVALID_FILE", "El contrato debe ser un PDF de hasta 10 MB", "file"));
  }
}
