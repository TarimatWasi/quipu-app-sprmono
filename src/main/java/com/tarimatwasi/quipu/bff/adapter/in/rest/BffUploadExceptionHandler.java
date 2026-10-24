package com.tarimatwasi.quipu.bff.adapter.in.rest;

import com.tarimatwasi.quipu.shared.file.FileInspectionBusyException;
import com.tarimatwasi.quipu.shared.web.UploadRoutes;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * The size limit of the multipart form (RF-15) is raised while the servlet reads the request,
 * before any controller has been chosen. An advice limited to the BFF package is skipped for a
 * request that has no handler yet, so this one has no package limit and answers only the errors of
 * the uploads: this one, and a PDF that could not be inspected in time. The answer depends on the
 * route: a contract is a PDF of up to 10 MB, a voucher or a document of the onboarding an image or
 * a PDF of up to 5 MB, and any other route gets the generic validation error. The route is matched
 * against the parsed and decoded path, as GuestAreaFilter does, never against the raw text of the
 * URI, which can be encoded differently.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class BffUploadExceptionHandler {

  private static final PathPatternRequestMatcher.Builder PATHS =
      PathPatternRequestMatcher.withDefaults();

  private static final RequestMatcher CONTRACTS = PATHS.matcher(UploadRoutes.CONTRACTS);

  private static final RequestMatcher VOUCHERS =
      new OrRequestMatcher(UploadRoutes.VOUCHERS.stream().map(PATHS::matcher).toList());

  private static final RequestMatcher DOCUMENTS =
      PATHS.matcher(UploadRoutes.GUEST_ONBOARDING_DOCUMENTS);

  /** A few seconds: an inspection takes well under one. */
  private static final int RETRY_SECONDS = 5;

  /** The form or its file is over the limit: the same answer as a file that is not valid. */
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<BffErrorResponse> handleUploadTooLarge(HttpServletRequest request) {
    BffErrorResponse body;
    if (CONTRACTS.matches(request)) {
      body =
          new BffErrorResponse(
              "CONTRACT_INVALID_FILE", "El contrato debe ser un PDF de hasta 10 MB", "file");
    } else if (VOUCHERS.matches(request)) {
      body =
          new BffErrorResponse(
              "VOUCHER_INVALID_FILE",
              "El comprobante debe ser una imagen JPG o PNG, o un PDF, de hasta 5 MB",
              "file");
    } else if (DOCUMENTS.matches(request)) {
      body = BffExceptionHandler.invalidDocumentFile();
    } else {
      body = new BffErrorResponse("VALIDATION_ERROR", "Datos de entrada inválidos");
    }
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
  }

  /**
   * TAR-39: another PDF kept the inspection too long. It is no verdict on this file, which may be
   * sent again: 503 where the route declares it, and 429 for the documents of the onboarding, whose
   * route declares no 503.
   */
  @ExceptionHandler(FileInspectionBusyException.class)
  public ResponseEntity<BffErrorResponse> handleInspectionBusy(HttpServletRequest request) {
    if (DOCUMENTS.matches(request)) {
      return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
          .header(HttpHeaders.RETRY_AFTER, String.valueOf(RETRY_SECONDS))
          .body(
              new BffErrorResponse(
                  "RATE_LIMITED",
                  "Demasiados intentos, espera un momento antes de volver a intentar"));
    }
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .header(HttpHeaders.RETRY_AFTER, String.valueOf(RETRY_SECONDS))
        .body(
            new BffErrorResponse(
                "FILE_STORAGE_UNAVAILABLE",
                "No se pudo acceder al archivo; inténtalo de nuevo más tarde"));
  }
}
