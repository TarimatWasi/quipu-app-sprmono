package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.support;

import com.tarimatwasi.quipu.auth.application.port.in.SessionFingerprintUseCase;
import com.tarimatwasi.quipu.auth.domain.event.AccountLocked;
import com.tarimatwasi.quipu.auth.domain.event.AccountsDeactivated;
import com.tarimatwasi.quipu.auth.domain.event.CredentialsChanged;
import com.tarimatwasi.quipu.auth.domain.event.SessionRevoked;
import com.tarimatwasi.quipu.contract.domain.event.ContractSigned;
import com.tarimatwasi.quipu.contract.domain.event.ContractTerminated;
import com.tarimatwasi.quipu.expense.domain.event.ExpenseCreated;
import com.tarimatwasi.quipu.expense.domain.event.ExpenseDeleted;
import com.tarimatwasi.quipu.expense.domain.event.ExpenseUpdated;
import com.tarimatwasi.quipu.fine.domain.event.FineApplied;
import com.tarimatwasi.quipu.fine.domain.event.FineDeleted;
import com.tarimatwasi.quipu.notification.domain.event.NotificationRaised;
import com.tarimatwasi.quipu.payment.domain.event.PaymentCorrected;
import com.tarimatwasi.quipu.payment.domain.event.PaymentRegistered;
import com.tarimatwasi.quipu.payment.domain.event.PaymentResolved;
import com.tarimatwasi.quipu.payment.domain.event.PaymentResubmitted;
import com.tarimatwasi.quipu.payment.domain.event.PaymentSubmitted;
import com.tarimatwasi.quipu.payment.domain.event.RefundCreated;
import com.tarimatwasi.quipu.shared.changes.ChangeSignals.Change;
import com.tarimatwasi.quipu.shared.changes.ChangeSignals.Kind;
import com.tarimatwasi.quipu.shared.events.DomainEvent;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Live invalidations of the open streams, derived from the domain events. It listens inside the
 * transaction that publishes them and only appends to the transaction buffer of {@link
 * ChangeStreamRegistry}, which delivers the whole transaction as one batch after the commit,
 * security closures first; a rollback delivers nothing. In memory and best effort, never an outbox
 * listener: an invalidation replayed after a restart is worthless.
 */
@Component
public class DomainEventInvalidations {
  private final ChangeStreamRegistry streams;
  private final SessionFingerprintUseCase sessions;

  public DomainEventInvalidations(
      ChangeStreamRegistry streams, SessionFingerprintUseCase sessions) {
    this.streams = streams;
    this.sessions = sessions;
  }

  /** Security facts close streams; the others invalidate resources. */
  @EventListener
  public void on(DomainEvent event) {
    switch (event) {
      case SessionRevoked e ->
          sessions
              .fingerprintOf(e.sessionId())
              .ifPresent(fingerprint -> streams.revokeSession(e.userId(), fingerprint));
      case CredentialsChanged e ->
          streams.credentialsChanged(
              e.userId(),
              e.securityVersion(),
              e.keptSessionId() == null
                  ? null
                  : sessions.fingerprintOf(e.keptSessionId()).orElse(null));
      case AccountLocked e -> streams.accountLocked(e.userId());
      case AccountsDeactivated e -> e.userIds().forEach(streams::accountLocked);
      default -> changesOf(event).forEach(streams::changed);
    }
  }

  /** The resource groups a fact touches; empty for facts no open screen shows. */
  static List<Change> changesOf(DomainEvent event) {
    return switch (event) {
      case ContractSigned e -> List.of(contract(e.guestId(), e.environmentId()));
      case ContractTerminated e -> List.of(contract(e.guestId(), e.environmentId()));
      case ExpenseCreated e -> List.of(administrative(Kind.EXPENSE));
      case ExpenseUpdated e -> List.of(administrative(Kind.EXPENSE));
      case ExpenseDeleted e -> List.of(administrative(Kind.EXPENSE));
      case FineApplied e -> List.of(new Change(Kind.FINE, e.guestId(), false, null, null));
      case FineDeleted e -> List.of(new Change(Kind.FINE, e.guestId(), false, null, null));
      case NotificationRaised e ->
          List.of(
              new Change(
                  Kind.NOTIFICATION,
                  e.forAdministrators() ? null : e.guestId(),
                  false,
                  null,
                  null));
      case PaymentSubmitted e ->
          List.of(
              payment(Kind.PAYMENT, e.guestId(), e.contractId(), e.paymentId(), e.environmentId()));
      case PaymentResubmitted e ->
          List.of(
              payment(Kind.PAYMENT, e.guestId(), e.contractId(), e.paymentId(), e.environmentId()));
      case PaymentCorrected e ->
          List.of(
              payment(Kind.PAYMENT, e.guestId(), e.contractId(), e.paymentId(), e.environmentId()));
      case PaymentResolved e ->
          List.of(
              payment(
                  Kind.FINANCIAL, e.guestId(), e.contractId(), e.paymentId(), e.environmentId()));
      case PaymentRegistered e ->
          List.of(
              payment(
                  Kind.FINANCIAL, e.guestId(), e.contractId(), e.paymentId(), e.environmentId()));
      case RefundCreated e ->
          List.of(new Change(Kind.FINANCIAL, e.guestId(), e.contract(), e.paymentId(), null));
      default -> List.of();
    };
  }

  private static Change payment(
      Kind kind,
      Long guestId,
      @Nullable Long contractId,
      Long paymentId,
      @Nullable Long environmentId) {
    return new Change(kind, guestId, contractId != null, paymentId, environmentId);
  }

  private static Change contract(Long guestId, Long environmentId) {
    return new Change(Kind.CONTRACT, guestId, true, null, environmentId);
  }

  private static Change administrative(Kind kind) {
    return new Change(kind, null, false, null, null);
  }
}
