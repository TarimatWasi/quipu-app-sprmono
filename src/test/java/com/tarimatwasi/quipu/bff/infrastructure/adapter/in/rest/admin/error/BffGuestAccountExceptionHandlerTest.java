package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.admin.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.auth.application.port.in.EmailVerificationException;
import com.tarimatwasi.quipu.auth.application.port.in.EmailVerificationException.Reason;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error.BffCooldownResponse;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error.BffErrorResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** TAR-247, TAR-349: the message is the literal text of the error catalog of SRS 17. */
class BffGuestAccountExceptionHandlerTest {

  private final BffGuestAccountExceptionHandler handler = new BffGuestAccountExceptionHandler();

  @Test
  void accountEmailTaken_answersTheCatalogTextOnTheEmailField() {
    ResponseEntity<BffErrorResponse> response = handler.handleAccountEmailTaken();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody())
        .isEqualTo(
            new BffErrorResponse(
                "ACCOUNT_EMAIL_TAKEN", "Ese correo ya está en uso por otra cuenta", "email"));
  }

  @Test
  void cooldown_answers429WithTheWaitInTheHeaderAndTheBody() {
    ResponseEntity<?> response =
        handler.handleEmailVerification(EmailVerificationException.cooldown(42));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("42");
    assertThat(response.getBody())
        .isEqualTo(
            new BffCooldownResponse(
                "EMAIL_VERIFICATION_COOLDOWN",
                "Espera un momento antes de pedir otro código",
                "email",
                42));
  }

  @ParameterizedTest
  @CsvSource({
    "UNCHANGED, EMAIL_UNCHANGED, email",
    "INVALID_CODE, EMAIL_VERIFICATION_INVALID, code",
    "EXPIRED, EMAIL_VERIFICATION_EXPIRED, code"
  })
  void aRefusedVerification_answers400OnItsField(Reason reason, String code, String field) {
    ResponseEntity<?> response =
        handler.handleEmailVerification(new EmailVerificationException(reason));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody())
        .isInstanceOfSatisfying(
            BffErrorResponse.class,
            body -> {
              assertThat(body.code()).isEqualTo(code);
              assertThat(body.field()).isEqualTo(field);
            });
  }
}
