package com.tarimatwasi.quipu.bff.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.shared.web.UploadRoutes;
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
}
