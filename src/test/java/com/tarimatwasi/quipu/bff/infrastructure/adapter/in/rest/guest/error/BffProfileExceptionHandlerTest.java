package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.guest.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error.BffErrorResponse;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error.BffExceptionHandler;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error.NullFieldException;
import com.tarimatwasi.quipu.guest.application.port.in.OwnProfileRefusedException;
import com.tarimatwasi.quipu.guest.application.port.in.OwnProfileRefusedException.Reason;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * The guest area filter refuses the profile of the other type of guest, or before the onboarding,
 * before any controller: these answers stand behind it, and must be the same ones.
 */
class BffProfileExceptionHandlerTest {

  private final BffProfileExceptionHandler handler =
      new BffProfileExceptionHandler(new BffExceptionHandler(mock(IdMasker.class)));

  @Test
  void aFieldThatCannotBeErasedIsAValidationErrorOfThatField() {
    BffErrorResponse body = body(handler.handleNullField(new NullFieldException("phone")));

    assertThat(body.code()).isEqualTo("VALIDATION_ERROR");
    assertThat(body.field()).isEqualTo("phone");
  }

  @Test
  void aRefusedProfileAnswersAsTheGuestAreaFilterDoes() {
    var otherType =
        handler.handleOwnProfileRefused(new OwnProfileRefusedException(Reason.OTHER_GUEST_TYPE));
    var onboarding =
        handler.handleOwnProfileRefused(new OwnProfileRefusedException(Reason.ONBOARDING_REQUIRED));

    assertThat(otherType.getStatusCode().value()).isEqualTo(403);
    assertThat(body(otherType).code()).isEqualTo("AUTH_FORBIDDEN");
    assertThat(onboarding.getStatusCode().value()).isEqualTo(403);
    assertThat(body(onboarding).code()).isEqualTo("AUTH_ONBOARDING_REQUIRED");
  }

  private static BffErrorResponse body(ResponseEntity<BffErrorResponse> response) {
    return Objects.requireNonNull(response.getBody());
  }
}
