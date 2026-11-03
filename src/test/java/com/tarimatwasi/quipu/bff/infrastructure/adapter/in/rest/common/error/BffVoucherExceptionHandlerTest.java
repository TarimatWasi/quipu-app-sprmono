package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.payment.application.port.in.InvalidVoucherFileException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

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
