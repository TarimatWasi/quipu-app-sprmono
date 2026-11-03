package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.admin.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error.BffErrorResponse;
import com.tarimatwasi.quipu.guest.application.port.in.GuestAccountEmailTakenException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** TAR-247: the message is the literal text of the error catalog of SRS 17. */
class BffGuestAccountExceptionHandlerTest {

  private final BffGuestAccountExceptionHandler handler = new BffGuestAccountExceptionHandler();

  @ParameterizedTest
  @CsvSource({"CONTACT_EMAIL, contactEmail", "EMAIL, email"})
  void accountEmailTaken_answersTheCatalogText(
      GuestAccountEmailTakenException.Field field, String name) {
    ResponseEntity<BffErrorResponse> response =
        handler.handleAccountEmailTaken(
            new GuestAccountEmailTakenException(field, new IllegalStateException("unique")));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody())
        .isEqualTo(
            new BffErrorResponse(
                "ACCOUNT_EMAIL_TAKEN", "Ese correo ya está en uso por otra cuenta", name));
  }
}
