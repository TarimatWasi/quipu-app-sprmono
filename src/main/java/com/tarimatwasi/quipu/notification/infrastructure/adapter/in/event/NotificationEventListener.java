package com.tarimatwasi.quipu.notification.infrastructure.adapter.in.event;

import com.tarimatwasi.quipu.contract.domain.event.ContractSigned;
import com.tarimatwasi.quipu.contract.domain.event.ContractTerminated;
import com.tarimatwasi.quipu.fine.domain.event.FineApplied;
import com.tarimatwasi.quipu.notification.application.port.in.RaiseNotificationUseCase;
import com.tarimatwasi.quipu.notification.application.port.in.RaiseNotificationUseCase.Kind;
import com.tarimatwasi.quipu.notification.application.port.in.RaiseNotificationUseCase.NewNotification;
import com.tarimatwasi.quipu.notification.application.port.in.RaiseNotificationUseCase.Target;
import com.tarimatwasi.quipu.shared.events.DomainEvent;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Who hears about which fact. This is the one place that decides it: the services that confirm the
 * facts know nothing about the bell. Durable (outbox) and idempotent by event id.
 */
@Component
public class NotificationEventListener {
  private final RaiseNotificationUseCase notifications;

  public NotificationEventListener(RaiseNotificationUseCase notifications) {
    this.notifications = notifications;
  }

  @ApplicationModuleListener
  public void on(FineApplied fine) {
    raise(
        fine,
        Kind.FINE_APPLIED,
        fine.guestId(),
        Target.FINE,
        fine.fineId(),
        Map.of("amount", fine.amount().toPlainString(), "month", fine.month().toString()));
  }

  @ApplicationModuleListener
  public void on(ContractSigned contract) {
    raise(
        contract,
        Kind.CONTRACT_SIGNED,
        contract.guestId(),
        Target.CONTRACT,
        contract.contractId(),
        Map.of(
            "startDate",
            contract.startDate().toString(),
            "monthlyAmount",
            contract.monthlyAmount().toPlainString()));
  }

  @ApplicationModuleListener
  public void on(ContractTerminated contract) {
    raise(
        contract,
        Kind.CONTRACT_TERMINATED,
        contract.guestId(),
        Target.CONTRACT,
        contract.contractId(),
        Map.of());
  }

  private void raise(
      DomainEvent event,
      Kind kind,
      @Nullable Long guestId,
      Target target,
      Long targetId,
      Map<String, String> facts) {
    notifications.raise(
        new NewNotification(
            event.metadata().eventId(),
            kind,
            guestId,
            target,
            targetId,
            facts,
            event.metadata().occurredAt()));
  }
}
