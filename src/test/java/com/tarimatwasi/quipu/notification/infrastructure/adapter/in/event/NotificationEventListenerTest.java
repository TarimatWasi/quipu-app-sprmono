package com.tarimatwasi.quipu.notification.infrastructure.adapter.in.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.tarimatwasi.quipu.notification.application.port.in.RaiseNotificationUseCase;
import com.tarimatwasi.quipu.notification.application.port.in.RaiseNotificationUseCase.Kind;
import com.tarimatwasi.quipu.notification.application.port.in.RaiseNotificationUseCase.NewNotification;
import com.tarimatwasi.quipu.notification.application.port.in.RaiseNotificationUseCase.Target;
import com.tarimatwasi.quipu.payment.domain.event.PaymentDue;
import com.tarimatwasi.quipu.shared.events.EventMetadata;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NotificationEventListenerTest {

  private static final EventMetadata META =
      new EventMetadata(UUID.randomUUID(), Instant.EPOCH, null);

  private final RaiseNotificationUseCase notifications = mock(RaiseNotificationUseCase.class);
  private final NotificationEventListener listener = new NotificationEventListener(notifications);

  /** A charge of a contract opens the contract; one of a stay, the guest. */
  @Test
  void aChargeDueOpensItsContractOrElseItsGuest() {
    listener.on(
        new PaymentDue(
            META, 5L, 7L, null, YearMonth.of(2026, 10), BigDecimal.TEN, LocalDate.of(2026, 10, 5)));
    listener.on(
        new PaymentDue(META, 5L, null, 8L, null, BigDecimal.TEN, LocalDate.of(2026, 10, 20)));

    var raised = ArgumentCaptor.forClass(NewNotification.class);
    verify(notifications, times(2)).raise(raised.capture());
    NewNotification contract = raised.getAllValues().get(0);
    NewNotification stay = raised.getAllValues().get(1);
    assertThat(contract.kind()).isEqualTo(Kind.PAYMENT_DUE);
    assertThat(contract.target()).isEqualTo(Target.CONTRACT);
    assertThat(contract.targetId()).isEqualTo(7L);
    assertThat(contract.facts()).containsEntry("month", "2026-10");
    assertThat(stay.target()).isEqualTo(Target.GUEST);
    assertThat(stay.targetId()).isEqualTo(5L);
    assertThat(stay.facts()).doesNotContainKey("month");
  }
}
