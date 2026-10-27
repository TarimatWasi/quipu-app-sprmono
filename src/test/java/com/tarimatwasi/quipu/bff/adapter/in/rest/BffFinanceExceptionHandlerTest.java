package com.tarimatwasi.quipu.bff.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** RF-18: a month of the balance that the base does not keep is a validation error of month. */
class BffFinanceExceptionHandlerTest {

  @Test
  void aMonthOfYearZeroIsAValidationErrorOfTheMonth() {
    var response = new BffFinanceExceptionHandler().handleInvalidFinanceMonth();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody())
        .isEqualTo(new BffErrorResponse("VALIDATION_ERROR", "Datos de entrada inválidos", "month"));
  }
}
