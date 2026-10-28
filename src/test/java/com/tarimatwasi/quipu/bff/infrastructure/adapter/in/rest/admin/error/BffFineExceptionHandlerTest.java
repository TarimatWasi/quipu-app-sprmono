package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.admin.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error.BffErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** RF-19: the codes of the contract for the errors of the fines; no guest is named. */
class BffFineExceptionHandlerTest {

  private final BffFineExceptionHandler handler = new BffFineExceptionHandler();

  @Test
  void aMissingFineIsAResourceNotFound() {
    var response = handler.handleFineNotFound();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody())
        .isEqualTo(new BffErrorResponse("RESOURCE_NOT_FOUND", "La multa no existe"));
  }

  @Test
  void aMissingGuestIsAResourceNotFound() {
    var response = handler.handleFineGuestNotFound();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody())
        .isEqualTo(new BffErrorResponse("RESOURCE_NOT_FOUND", "El huésped no existe"));
  }

  @Test
  void anInvalidAmountOrDescriptionIsAValidationErrorNamingTheField() {
    var amount = handler.handleInvalidFine();
    var description = handler.handleInvalidFineDescription();

    assertThat(amount.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(amount.getBody())
        .extracting(BffErrorResponse::code, BffErrorResponse::field)
        .containsExactly("VALIDATION_ERROR", "amount");
    assertThat(handler.handleInvalidFineMonth().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(handler.handleInvalidFineMonth().getBody())
        .extracting(BffErrorResponse::code, BffErrorResponse::field)
        .containsExactly("VALIDATION_ERROR", "month");
    assertThat(description.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(description.getBody())
        .extracting(BffErrorResponse::code, BffErrorResponse::field)
        .containsExactly("VALIDATION_ERROR", "description");
  }
}
