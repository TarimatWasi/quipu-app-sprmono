package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tarimatwasi.quipu.notification.application.port.in.RaiseNotificationUseCase.Kind;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class NotificationTextsTest {

  @Test
  void theGuestIsNamedWhereTheSentenceNeedsIt() {
    assertThat(NotificationTexts.of(Kind.ONBOARDING_COMPLETED, Map.of(), "Ana Quispe").body())
        .isEqualTo("Ana Quispe terminó su registro.");
    assertThat(
            NotificationTexts.of(Kind.STAY_ENDING, Map.of("endDate", "2026-10-15"), "Luis Mamani")
                .body())
        .isEqualTo("La estadía de Luis Mamani termina el 15 de octubre de 2026.");
  }

  @Test
  void aGuestWithoutNameIsAGuest() {
    assertThat(NotificationTexts.of(Kind.ONBOARDING_COMPLETED, Map.of()).body())
        .isEqualTo("Un huésped terminó su registro.");
    assertThat(
            NotificationTexts.of(Kind.ACCESS_EXPIRED, Map.of("expiredOn", "2026-10-13"), null)
                .body())
        .isEqualTo(
            "El acceso de un huésped venció el 13 de octubre de 2026 sin usarse: entrega"
                + " uno nuevo.");
  }

  /** The period is named for a contract; a stay has none. */
  @Test
  void theProofOfAStayNamesNoPeriod() {
    assertThat(
            NotificationTexts.of(Kind.PAYMENT_PROOF_SUBMITTED, Map.of("amount", "150"), null)
                .body())
        .isEqualTo("Un huésped envió un pago de S/ 150.00 para revisar.");
  }

  @Test
  void aKindWithoutATextIsRefused() {
    assertThatThrownBy(() -> NotificationTexts.requireEveryKind(Map.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("has no text");
  }

  /** No template is left with a fact it cannot fill. */
  @ParameterizedTest
  @EnumSource(Kind.class)
  void everyKindFillsAllItsFacts(Kind kind) {
    Map<String, String> facts =
        Map.of(
            "amount", "10",
            "monthlyAmount", "600",
            "month", "2026-10",
            "dueDate", "2026-10-10",
            "startDate", "2026-10-01",
            "endDate", "2026-11-01",
            "expiredOn", "2026-10-13");

    assertThat(NotificationTexts.of(kind, facts, "Ana Quispe").body()).doesNotContain("{", "}");
  }
}
