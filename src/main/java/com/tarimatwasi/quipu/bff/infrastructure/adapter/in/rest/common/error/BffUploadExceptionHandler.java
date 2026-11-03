package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error;

import com.tarimatwasi.quipu.shared.file.FileInspectionBusyException;
import com.tarimatwasi.quipu.shared.web.UploadRoutes;
import jakarta.servlet.http.HttpServletRequest;
import java.util.stream.Stream;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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

  /** A payment sent with its vouchers, or one whose proofs are corrected (TAR-239). */
  private static final RequestMatcher VOUCHERS =
      new OrRequestMatcher(
          Stream.concat(
                  UploadRoutes.VOUCHERS.stream().map(PATHS::matcher),
                  UploadRoutes.PAYMENT_CORRECTIONS.stream()
                      .map(route -> PATHS.matcher(HttpMethod.PUT, route)))
              .toList());

  /**
   * A document a guest uploads (onboarding or «Mis datos», TAR-242), or the one an administrator
   * puts in place of another.
   */
  private static final RequestMatcher DOCUMENTS =
      new OrRequestMatcher(
          Stream.concat(
                  UploadRoutes.GUEST_DOCUMENT_UPLOADS.stream().map(PATHS::matcher),
                  Stream.of(PATHS.matcher(HttpMethod.PUT, UploadRoutes.GUEST_DOCUMENT_REPLACEMENT)))
              .toList());

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
              BffVoucherExceptionHandler.INVALID_VOUCHER_FILE_MESSAGE,
              "file");
    } else if (DOCUMENTS.matches(request)) {
      body = BffExceptionHandler.invalidDocumentFile();
    } else {
      body = new BffErrorResponse("VALIDATION_ERROR", "Datos de entrada inválidos");
    }
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
  }

  /**
   * TAR-39: the PDF could not be inspected in time (another one held the inspection, or its own ran
   * past its deadline). It is no verdict on this file, which may be sent again: 503 where the route
   * declares it, and 429 for the documents of the onboarding, whose route declares no 503, and for
   * the replacement of a document, whose 503 is the storage's (TAR-187).
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
