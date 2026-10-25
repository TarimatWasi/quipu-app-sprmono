package com.tarimatwasi.quipu.bff.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.shared.web.UploadRoutes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * RF-15: the code of a form over the limit follows the route as it is matched, decoded and
 * normalized, never the raw text of the URI; a route that takes no file gets the generic 400.
 */
class BffUploadExceptionHandlerTest {

  private final BffUploadExceptionHandler handler = new BffUploadExceptionHandler();

  @ParameterizedTest
  @CsvSource({
    UploadRoutes.CONTRACTS + ", CONTRACT_INVALID_FILE",
    UploadRoutes.ADMIN_PAYMENTS + ", VOUCHER_INVALID_FILE",
    UploadRoutes.GUEST_PAYMENTS + ", VOUCHER_INVALID_FILE",
    UploadRoutes.TEMPORARY_GUEST_PAYMENTS + ", VOUCHER_INVALID_FILE",
    UploadRoutes.GUEST_ONBOARDING_DOCUMENTS + ", DOCUMENT_INVALID_FILE",
    "/bff/temporary%2Dguest/payments, VOUCHER_INVALID_FILE",
    "/bff/admin/contr%61cts, CONTRACT_INVALID_FILE",
    "/bff/guest/onboarding/documents/, VALIDATION_ERROR",
    "/bff/admin/contracts/x, VALIDATION_ERROR",
    "/bff/admin/environments, VALIDATION_ERROR"
  })
  void theCodeFollowsTheMatchedRoute(String uri, String code) {
    var response = handler.handleUploadTooLarge(post(uri));

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(response.getBody()).extracting(BffErrorResponse::code).isEqualTo(code);
  }

  private static MockHttpServletRequest post(String uri) {
    return new MockHttpServletRequest("POST", uri);
  }

  private static MockHttpServletRequest put(String uri) {
    return new MockHttpServletRequest("PUT", uri);
  }

  private static final String REPLACEMENT = "/bff/admin/guests/a/documents/b";

  @Test
  void theReplacementOfADocumentAnswersAsADocument() {
    // TAR-187: over the limit it is a DOCUMENT_INVALID_FILE, never a 413; a busy inspection a 429
    assertThat(handler.handleUploadTooLarge(put(REPLACEMENT)).getBody())
        .extracting(BffErrorResponse::code)
        .isEqualTo("DOCUMENT_INVALID_FILE");
    var busy = handler.handleInspectionBusy(put(REPLACEMENT));
    assertThat(busy.getStatusCode().value()).isEqualTo(429);
    assertThat(busy.getBody()).extracting(BffErrorResponse::code).isEqualTo("RATE_LIMITED");
    assertThat(busy.getHeaders().getFirst("Retry-After")).isNotBlank();
    // only by PUT and only on that exact shape
    assertThat(handler.handleUploadTooLarge(post(REPLACEMENT)).getBody())
        .extracting(BffErrorResponse::code)
        .isEqualTo("VALIDATION_ERROR");
    assertThat(handler.handleUploadTooLarge(put(REPLACEMENT + "/deletion")).getBody())
        .extracting(BffErrorResponse::code)
        .isEqualTo("VALIDATION_ERROR");
  }

  @ParameterizedTest
  @CsvSource({
    UploadRoutes.CONTRACTS + ", 503, FILE_STORAGE_UNAVAILABLE",
    UploadRoutes.ADMIN_PAYMENTS + ", 503, FILE_STORAGE_UNAVAILABLE",
    UploadRoutes.GUEST_PAYMENTS + ", 503, FILE_STORAGE_UNAVAILABLE",
    UploadRoutes.TEMPORARY_GUEST_PAYMENTS + ", 503, FILE_STORAGE_UNAVAILABLE",
    UploadRoutes.GUEST_ONBOARDING_DOCUMENTS + ", 429, RATE_LIMITED"
  })
  void aBusyInspectionIsNoVerdictOnTheFile(String uri, int status, String code) {
    // TAR-39: 503 where the route declares it; the documents declare 429 and no 503
    var response = handler.handleInspectionBusy(post(uri));

    assertThat(response.getStatusCode().value()).isEqualTo(status);
    assertThat(response.getBody()).extracting(BffErrorResponse::code).isEqualTo(code);
    assertThat(response.getHeaders().getFirst("Retry-After")).isNotBlank();
  }
}
