package com.tarimatwasi.quipu.bff.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class BffExceptionHandlerTest {

  private final BffExceptionHandler handler = new BffExceptionHandler();

  /** BE-SPR-DAT-13, QP-SPRMONO-BFF-01 (TAR-171): code in English, message in Spanish. */
  @Test
  void concurrentModification_answers409WithTheBffErrorFormat() {
    ResponseEntity<BffErrorResponse> response = handler.handleConcurrentModification();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody())
        .isEqualTo(
            new BffErrorResponse(
                "CONCURRENT_MODIFICATION",
                "El registro cambió mientras lo editabas; vuelve a cargarlo e inténtalo de nuevo"));
  }
}
