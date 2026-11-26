package com.tarimatwasi.quipu.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationTest {

  @Test
  void facts_acceptAmountsMonthsAndDates() {
    var notification =
        Notification.raised(
            UUID.randomUUID(),
            NotificationType.FINE_APPLIED,
            7L,
            TargetKind.FINE,
            3L,
            Map.of("amount", "15.00", "month", "2026-10", "day", "2026-10-01"),
            Instant.EPOCH);

    assertThat(notification.audience()).isEqualTo(Audience.GUEST);
    assertThat(notification.readAt()).isNull();
  }

  @Test
  void facts_refuseFreeText() {
    assertThatThrownBy(
            () ->
                Notification.raised(
                    UUID.randomUUID(),
                    NotificationType.FINE_APPLIED,
                    7L,
                    TargetKind.FINE,
                    3L,
                    Map.of("description", "Ruido de Ana"),
                    Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Not a fact value: description");
  }

  @Test
  void guestNotification_namesItsGuest() {
    assertThatThrownBy(
            () ->
                Notification.raised(
                    UUID.randomUUID(),
                    NotificationType.CONTRACT_SIGNED,
                    null,
                    TargetKind.CONTRACT,
                    3L,
                    Map.of(),
                    Instant.EPOCH))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void administratorNotification_mayBeAboutNoGuest() {
    var notification =
        Notification.raised(
            UUID.randomUUID(),
            NotificationType.CONTRACT_EXPIRING,
            null,
            TargetKind.CONTRACT,
            3L,
            Map.of(),
            Instant.EPOCH);

    assertThat(notification.audience()).isEqualTo(Audience.ADMINISTRATORS);
  }
}
