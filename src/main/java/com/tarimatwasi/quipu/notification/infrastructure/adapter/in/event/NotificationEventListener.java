package com.tarimatwasi.quipu.notification.infrastructure.adapter.in.event;

import com.tarimatwasi.quipu.contract.domain.event.ContractExpiring;
import com.tarimatwasi.quipu.contract.domain.event.ContractSigned;
import com.tarimatwasi.quipu.contract.domain.event.ContractTerminated;
import com.tarimatwasi.quipu.fine.domain.event.FineApplied;
import com.tarimatwasi.quipu.guest.domain.event.AccessExpired;
import com.tarimatwasi.quipu.guest.domain.event.GuestDocumentUploaded;
import com.tarimatwasi.quipu.guest.domain.event.OnboardingCompleted;
import com.tarimatwasi.quipu.guest.domain.event.StayEnding;
import com.tarimatwasi.quipu.notification.application.port.in.RaiseNotificationUseCase;
import com.tarimatwasi.quipu.notification.application.port.in.RaiseNotificationUseCase.Kind;
import com.tarimatwasi.quipu.notification.application.port.in.RaiseNotificationUseCase.NewNotification;
import com.tarimatwasi.quipu.notification.application.port.in.RaiseNotificationUseCase.Target;
import com.tarimatwasi.quipu.payment.domain.event.PaymentDue;
import com.tarimatwasi.quipu.payment.domain.event.PaymentOverdue;
import com.tarimatwasi.quipu.payment.domain.event.PaymentResolved;
import com.tarimatwasi.quipu.payment.domain.event.PaymentResubmitted;
import com.tarimatwasi.quipu.payment.domain.event.PaymentSubmitted;
import com.tarimatwasi.quipu.payment.domain.event.RefundCreated;
import com.tarimatwasi.quipu.shared.events.DomainEvent;
import com.tarimatwasi.quipu.shared.events.delivery.DurableListener;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Who hears about which fact. This is the one place that decides it: the services that confirm the
 * facts know nothing about the bell. Durable (outbox) and idempotent by event id.
 */
@Component
public class NotificationEventListener {
  /** The days of the facts are the days of Lima. */
  private static final ZoneId LIMA = ZoneId.of("America/Lima");

  private final RaiseNotificationUseCase notifications;

  public NotificationEventListener(RaiseNotificationUseCase notifications) {
    this.notifications = notifications;
  }

  @DurableListener
  public void on(FineApplied fine) {
    raise(
        fine,
        Kind.FINE_APPLIED,
        fine.guestId(),
        Target.FINE,
        fine.fineId(),
        Map.of("amount", fine.amount().toPlainString(), "month", fine.month().toString()));
  }

  @DurableListener
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

  @DurableListener
  public void on(ContractTerminated contract) {
    raise(
        contract,
        Kind.CONTRACT_TERMINATED,
        contract.guestId(),
        Target.CONTRACT,
        contract.contractId(),
        Map.of());
  }

  @DurableListener
  public void on(PaymentSubmitted payment) {
    raise(
        payment,
        Kind.PAYMENT_PROOF_SUBMITTED,
        payment.guestId(),
        Target.PAYMENT,
        payment.paymentId(),
        paid(payment.amount(), payment.period()));
  }

  @DurableListener
  public void on(PaymentResubmitted payment) {
    raise(
        payment,
        Kind.PAYMENT_PROOF_SUBMITTED,
        payment.guestId(),
        Target.PAYMENT,
        payment.paymentId(),
        paid(payment.amount(), payment.period()));
  }

  @DurableListener
  public void on(PaymentResolved payment) {
    raise(
        payment,
        payment.approved() ? Kind.PAYMENT_APPROVED : Kind.PAYMENT_REJECTED,
        payment.guestId(),
        Target.PAYMENT,
        payment.paymentId(),
        paid(payment.amount(), payment.period()));
  }

  @DurableListener
  public void on(RefundCreated refund) {
    raise(
        refund,
        Kind.REFUND_CREATED,
        refund.guestId(),
        Target.REFUND,
        refund.refundId(),
        Map.of("amount", refund.amount().toPlainString()));
  }

  @DurableListener
  public void on(OnboardingCompleted onboarding) {
    raise(
        onboarding,
        Kind.ONBOARDING_COMPLETED,
        onboarding.guestId(),
        Target.GUEST,
        onboarding.guestId(),
        Map.of());
  }

  @DurableListener
  public void on(GuestDocumentUploaded document) {
    raise(
        document,
        Kind.GUEST_DOCUMENT_UPLOADED,
        document.guestId(),
        Target.DOCUMENT,
        document.documentId(),
        Map.of());
  }

  @DurableListener
  public void on(PaymentDue due) {
    raise(
        due,
        Kind.PAYMENT_DUE,
        due.guestId(),
        due.contractId() != null ? Target.CONTRACT : Target.GUEST,
        due.contractId() != null ? due.contractId() : due.guestId(),
        dated(paid(due.amount(), due.period()), due.dueDate()));
  }

  @DurableListener
  public void on(PaymentOverdue overdue) {
    raise(
        overdue,
        Kind.PAYMENT_OVERDUE,
        overdue.guestId(),
        Target.GUEST,
        overdue.guestId(),
        dated(paid(overdue.amount(), overdue.period()), overdue.dueDate()));
  }

  @DurableListener
  public void on(ContractExpiring contract) {
    raise(
        contract,
        Kind.CONTRACT_EXPIRING,
        contract.guestId(),
        Target.CONTRACT,
        contract.contractId(),
        Map.of("endDate", contract.endDate().toString()));
  }

  @DurableListener
  public void on(StayEnding stay) {
    raise(
        stay,
        Kind.STAY_ENDING,
        stay.guestId(),
        Target.GUEST,
        stay.guestId(),
        Map.of("endDate", stay.endDate().toString()));
  }

  @DurableListener
  public void on(AccessExpired access) {
    raise(
        access,
        Kind.ACCESS_EXPIRED,
        access.guestId(),
        Target.GUEST,
        access.guestId(),
        Map.of("expiredOn", LocalDate.ofInstant(access.expiredAt(), LIMA).toString()));
  }

  /** The facts with the due date of the charge. */
  private static Map<String, String> dated(Map<String, String> facts, LocalDate dueDate) {
    Map<String, String> all = new HashMap<>(facts);
    all.put("dueDate", dueDate.toString());
    return all;
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
