package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.auth.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.auth.application.port.in.PasswordUnchangedException;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error.BffErrorResponse;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class BffPasswordExceptionHandlerTest {

  private final BffPasswordExceptionHandler handler = new BffPasswordExceptionHandler();

  /** TAR-127 (A0.6): only the first access has a temporary password to name. */
  @Test
  void theSamePasswordNamesTheTemporaryOneOnlyOnTheFirstAccess() {
    assertThat(
            body(handler.handlePasswordUnchanged(new PasswordUnchangedException(true))).message())
        .isEqualTo("Elige una contraseña distinta de la temporal");
    BffErrorResponse later =
        body(handler.handlePasswordUnchanged(new PasswordUnchangedException(false)));
    assertThat(later.message()).isEqualTo("Elige una contraseña distinta de la actual");
    assertThat(later.field()).isEqualTo("newPassword");
  }

  /** TAR-127 (A0.5): the screen asks for the name again; the code was not consumed. */
  @Test
  void anActivationWithoutNameIsAValidationErrorOfTheName() {
    var response = handler.handleAdministratorNameRequired();

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(body(response).code()).isEqualTo("VALIDATION_ERROR");
    assertThat(body(response).field()).isEqualTo("name");
  }

  private static BffErrorResponse body(ResponseEntity<BffErrorResponse> response) {
    return Objects.requireNonNull(response.getBody());
  }
}
