package com.tarimatwasi.quipu.bff.adapter.in.rest;

import com.tarimatwasi.quipu.shared.web.UploadRoutes;
import jakarta.servlet.http.HttpServletRequest;
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
 * The answer depends on the route: a contract is a PDF of up to 10 MB, a voucher or a document of
 * the onboarding an image or a PDF of up to 5 MB.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class BffUploadExceptionHandler {

  /** The form or its file is over the limit: the same answer as a file that is not valid. */
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<BffErrorResponse> handleUploadTooLarge(HttpServletRequest request) {
    String route = request.getRequestURI();
    BffErrorResponse body;
    if (UploadRoutes.VOUCHERS.contains(route)) {
      body =
          new BffErrorResponse(
              "VOUCHER_INVALID_FILE",
              "El comprobante debe ser una imagen JPG o PNG, o un PDF, de hasta 5 MB",
              "file");
    } else if (UploadRoutes.GUEST_ONBOARDING_DOCUMENTS.equals(route)) {
      body = BffExceptionHandler.invalidDocumentFile();
    } else {
      body =
          new BffErrorResponse(
              "CONTRACT_INVALID_FILE", "El contrato debe ser un PDF de hasta 10 MB", "file");
    }
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
  }
}
