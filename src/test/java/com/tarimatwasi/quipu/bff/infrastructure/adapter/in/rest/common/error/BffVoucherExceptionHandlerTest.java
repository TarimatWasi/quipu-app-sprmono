package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.payment.application.port.in.InvalidVoucherFileException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/** TAR-247: the messages are the literal texts of the error catalog of SRS 17. */
class BffVoucherExceptionHandlerTest {

  private final BffVoucherExceptionHandler handler = new BffVoucherExceptionHandler();

  @Test
  void invalidVoucherFile_answersTheCatalogText() {
    ResponseEntity<BffErrorResponse> response =
        handler.handleInvalidVoucherFile(new InvalidVoucherFileException("file"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody())
        .isEqualTo(
            new BffErrorResponse(
                "VOUCHER_INVALID_FILE",
                "Archivo inválido — sube JPG, PNG o PDF de máx. 5MB",
                "file"));
  }

  /** TAR-247, H3: the guest with a contract already sent the payment of the period. */
  @Test
  void voucherAlreadyExists_toTheGuestWithAContract_speaksOfThePeriod() {
    assertVoucherAlreadyExists(
        "/bff/guest/payments",
        "Ya enviaste un pago para este período y la administración lo está revisando.");
  }

  /** TAR-247, T2: the temporary guest already sent the payment of the stay. */
  @Test
  void voucherAlreadyExists_toTheTemporaryGuest_speaksOfTheStay() {
    assertVoucherAlreadyExists(
        "/bff/temporary-guest/payments",
        "Ya enviaste un pago para esta estadía y la administración lo está revisando.");
  }

  /** TAR-247, A5.1: the administrator registering a payment is sent to «Revisar pago». */
  @Test
  void voucherAlreadyExists_toTheAdministration_sendsItToReviewThePayment() {
    assertVoucherAlreadyExists(
        "/bff/admin/payments", "Este período ya tiene un pago: complétalo en Revisar pago.");
  }

  private void assertVoucherAlreadyExists(String path, String message) {
    ResponseEntity<BffErrorResponse> response =
        handler.handleVoucherAlreadyExists(new MockHttpServletRequest("POST", path));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody())
        .isEqualTo(new BffErrorResponse("VOUCHER_ALREADY_EXISTS", message));
  }

  @Test
  void noActiveContract_answersTheCatalogText() {
    ResponseEntity<BffErrorResponse> response = handler.handleNoActiveContract();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(response.getBody())
        .isEqualTo(
            new BffErrorResponse(
                "VOUCHER_NO_ACTIVE_CONTRACT", "No tienes un contrato vigente activo"));
  }
}
