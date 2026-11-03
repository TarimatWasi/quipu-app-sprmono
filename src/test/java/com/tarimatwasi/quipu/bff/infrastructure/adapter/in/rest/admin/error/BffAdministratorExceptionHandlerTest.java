package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.admin.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.auth.application.port.in.AdministratorLimitReachedException;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error.BffErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** TAR-247: the messages are the literal texts of the error catalog of SRS 17. */
class BffAdministratorExceptionHandlerTest {

  private final BffAdministratorExceptionHandler handler = new BffAdministratorExceptionHandler();

  @Test
  void emailTaken_answersTheCatalogText() {
    ResponseEntity<BffErrorResponse> response = handler.handleEmailTaken();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody())
        .isEqualTo(
            new BffErrorResponse(
                "ACCOUNT_EMAIL_TAKEN", "Ese correo ya está en uso por otra cuenta", "email"));
  }

  @Test
  void limitReached_answersTheCatalogTextWithTheDefaultMaximum() {
    ResponseEntity<BffErrorResponse> response =
        handler.handleLimitReached(new AdministratorLimitReachedException(5));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody())
        .isEqualTo(
            new BffErrorResponse(
                "ADMIN_LIMIT_REACHED",
                "Ya hay 5 administradores, el máximo. Desactiva uno antes de crear otro."));
  }

  /** The number is the configured maximum ({@code app.administrators.max}), never a fixed 5. */
  @Test
  void limitReached_namesTheConfiguredMaximum() {
    assertThat(handler.handleLimitReached(new AdministratorLimitReachedException(3)).getBody())
        .isEqualTo(
            new BffErrorResponse(
                "ADMIN_LIMIT_REACHED",
                "Ya hay 3 administradores, el máximo. Desactiva uno antes de crear otro."));
  }

  @Test
  void administratorNotFound_answersTheCatalogText() {
    ResponseEntity<BffErrorResponse> response = handler.handleAdministratorNotFound();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody())
        .isEqualTo(new BffErrorResponse("ADMIN_NOT_FOUND", "Ese administrador no existe"));
  }
}
