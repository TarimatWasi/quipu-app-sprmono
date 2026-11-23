package com.tarimatwasi.quipu.notification.infrastructure.adapter.in.event;

import com.tarimatwasi.quipu.contract.domain.event.ContractSigned;
import com.tarimatwasi.quipu.contract.domain.event.ContractTerminated;
import com.tarimatwasi.quipu.fine.domain.event.FineApplied;
import com.tarimatwasi.quipu.guest.domain.event.GuestDocumentUploaded;
import com.tarimatwasi.quipu.guest.domain.event.OnboardingCompleted;
import com.tarimatwasi.quipu.notification.application.port.in.RaiseNotificationUseCase;
import com.tarimatwasi.quipu.notification.application.port.in.RaiseNotificationUseCase.Kind;
import com.tarimatwasi.quipu.notification.application.port.in.RaiseNotificationUseCase.NewNotification;
import com.tarimatwasi.quipu.notification.application.port.in.RaiseNotificationUseCase.Target;
import com.tarimatwasi.quipu.payment.domain.event.PaymentResolved;
import com.tarimatwasi.quipu.payment.domain.event.PaymentResubmitted;
import com.tarimatwasi.quipu.payment.domain.event.PaymentSubmitted;
import com.tarimatwasi.quipu.payment.domain.event.RefundCreated;
import com.tarimatwasi.quipu.shared.events.DomainEvent;
import java.math.BigDecimal;
import java.time.YearMonth;
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

  @ApplicationModuleListener
  public void on(PaymentSubmitted payment) {
    raise(
        payment,
        Kind.PAYMENT_PROOF_SUBMITTED,
        payment.guestId(),
        Target.PAYMENT,
        payment.paymentId(),
        paid(payment.amount(), payment.period()));
  }

  @ApplicationModuleListener
  public void on(PaymentResubmitted payment) {
    raise(
        payment,
        Kind.PAYMENT_PROOF_SUBMITTED,
        payment.guestId(),
        Target.PAYMENT,
        payment.paymentId(),
        paid(payment.amount(), payment.period()));
  }

  @ApplicationModuleListener
  public void on(PaymentResolved payment) {
    raise(
        payment,
        payment.approved() ? Kind.PAYMENT_APPROVED : Kind.PAYMENT_REJECTED,
        payment.guestId(),
        Target.PAYMENT,
        payment.paymentId(),
        paid(payment.amount(), payment.period()));
  }

  @ApplicationModuleListener
  public void on(RefundCreated refund) {
    raise(
        refund,
        Kind.REFUND_CREATED,
        refund.guestId(),
        Target.REFUND,
        refund.refundId(),
        Map.of("amount", refund.amount().toPlainString()));
  }

  @ApplicationModuleListener
  public void on(OnboardingCompleted onboarding) {
    raise(
        onboarding,
        Kind.ONBOARDING_COMPLETED,
        onboarding.guestId(),
        Target.GUEST,
        onboarding.guestId(),
        Map.of());
  }

  @ApplicationModuleListener
  public void on(GuestDocumentUploaded document) {
    raise(
        document,
        Kind.GUEST_DOCUMENT_UPLOADED,
        document.guestId(),
        Target.DOCUMENT,
        document.documentId(),
        Map.of());
  }

  /** The amount and, for a period of a contract, its month. */
  private static Map<String, String> paid(BigDecimal amount, @Nullable YearMonth period) {
    return period == null
        ? Map.of("amount", amount.toPlainString())
        : Map.of("amount", amount.toPlainString(), "month", period.toString());
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
