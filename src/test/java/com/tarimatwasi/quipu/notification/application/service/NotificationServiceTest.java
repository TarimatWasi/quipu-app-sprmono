package com.tarimatwasi.quipu.notification.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.notification.application.port.in.NotificationNotFoundException;
import com.tarimatwasi.quipu.notification.application.port.in.RaiseNotificationUseCase.Kind;
import com.tarimatwasi.quipu.notification.application.port.in.RaiseNotificationUseCase.NewNotification;
import com.tarimatwasi.quipu.notification.application.port.in.RaiseNotificationUseCase.Raised;
import com.tarimatwasi.quipu.notification.application.port.in.RaiseNotificationUseCase.Target;
import com.tarimatwasi.quipu.notification.application.port.in.ReadNotificationsUseCase.Reader;
import com.tarimatwasi.quipu.notification.application.port.out.NotificationRepositoryPort;
import com.tarimatwasi.quipu.notification.application.port.out.NotificationRepositoryPort.Inbox;
import com.tarimatwasi.quipu.notification.domain.Audience;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationServiceTest {
  private static final Instant NOW = Instant.parse("2026-11-22T15:00:00Z");

  private final NotificationRepositoryPort repository = mock(NotificationRepositoryPort.class);
  private final NotificationService service =
      new NotificationService(repository, Clock.fixed(NOW, ZoneOffset.UTC));

  private static NewNotification fine() {
    return new NewNotification(
        UUID.randomUUID(),
        Kind.FINE_APPLIED,
        7L,
        Target.FINE,
        3L,
        Map.of("amount", "15.00", "month", "2026-10"),
        NOW);
  }

  @Test
  void raise_storesItOnceAndPurgesExpiredNotifications() {
    when(repository.insert(any())).thenReturn(Optional.of(11L));

    Raised raised = service.raise(fine());

    assertThat(raised).isEqualTo(new Raised(11L, false, 7L, true));
    verify(repository)
        .deleteCreatedBefore(
            eq(NOW.minus(NotificationService.RETENTION)), eq(NotificationService.PURGE_BATCH));
  }

  @Test
  void raise_ofARedeliveredEvent_statesNoNewFact() {
    when(repository.insert(any())).thenReturn(Optional.empty());

    assertThat(service.raise(fine()).happened()).isFalse();
  }

  @Test
  void read_ofAnotherInbox_isNotFound() {
    when(repository.markRead(any(), eq(5L), any())).thenReturn(false);

    assertThatThrownBy(() -> service.read(new Reader(1L, 7L), 5L))
        .isInstanceOf(NotificationNotFoundException.class);
  }

  @Test
  void administrator_readsTheSharedInbox() {
    service.readAll(new Reader(1L, null));

    verify(repository).markAllRead(new Inbox(Audience.ADMINISTRATORS, null, 1L), NOW);
  }

  @Test
  void guest_readsOnlyItsOwnInbox() {
    service.readAll(new Reader(2L, 7L));

    verify(repository).markAllRead(new Inbox(Audience.GUEST, 7L, 2L), NOW);
  }
}
