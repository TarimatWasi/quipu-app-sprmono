package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.support;

import com.tarimatwasi.quipu.auth.application.port.in.SessionFingerprintUseCase;
import com.tarimatwasi.quipu.auth.domain.event.AccountLocked;
import com.tarimatwasi.quipu.auth.domain.event.AccountsDeactivated;
import com.tarimatwasi.quipu.auth.domain.event.CredentialsChanged;
import com.tarimatwasi.quipu.auth.domain.event.GuestAccountEmailChanged;
import com.tarimatwasi.quipu.auth.domain.event.SessionRevoked;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.support.ChangeSignals.Change;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.support.ChangeSignals.Kind;
import com.tarimatwasi.quipu.calendar.domain.event.BusinessDayStarted;
import com.tarimatwasi.quipu.contract.domain.event.ContractSigned;
import com.tarimatwasi.quipu.contract.domain.event.ContractTerminated;
import com.tarimatwasi.quipu.environment.application.port.in.ManageEnvironmentsUseCase;
import com.tarimatwasi.quipu.environment.domain.event.EnvironmentCreated;
import com.tarimatwasi.quipu.environment.domain.event.EnvironmentDeactivated;
import com.tarimatwasi.quipu.environment.domain.event.EnvironmentReactivated;
import com.tarimatwasi.quipu.environment.domain.event.EnvironmentRelabeled;
import com.tarimatwasi.quipu.environment.domain.event.EnvironmentUpdated;
import com.tarimatwasi.quipu.expense.domain.event.ExpenseCreated;
import com.tarimatwasi.quipu.expense.domain.event.ExpenseDeleted;
import com.tarimatwasi.quipu.expense.domain.event.ExpenseUpdated;
import com.tarimatwasi.quipu.fine.domain.event.FineApplied;
import com.tarimatwasi.quipu.fine.domain.event.FineDeleted;
import com.tarimatwasi.quipu.guest.domain.event.DocumentsStepFinished;
import com.tarimatwasi.quipu.guest.domain.event.GuestAccessIssued;
import com.tarimatwasi.quipu.guest.domain.event.GuestAccessUsed;
import com.tarimatwasi.quipu.guest.domain.event.GuestDeactivated;
import com.tarimatwasi.quipu.guest.domain.event.GuestDocumentDeleted;
import com.tarimatwasi.quipu.guest.domain.event.GuestDocumentDeletionRequested;
import com.tarimatwasi.quipu.guest.domain.event.GuestDocumentReplaced;
import com.tarimatwasi.quipu.guest.domain.event.GuestDocumentUploaded;
import com.tarimatwasi.quipu.guest.domain.event.GuestIdentityCorrected;
import com.tarimatwasi.quipu.guest.domain.event.GuestReactivated;
import com.tarimatwasi.quipu.guest.domain.event.GuestRegistered;
import com.tarimatwasi.quipu.guest.domain.event.OnboardingCompleted;
import com.tarimatwasi.quipu.guest.domain.event.PersonalDataSaved;
import com.tarimatwasi.quipu.guest.domain.event.ProfileUpdated;
import com.tarimatwasi.quipu.guest.domain.event.StayCreated;
import com.tarimatwasi.quipu.guest.domain.event.StayUpdated;
import com.tarimatwasi.quipu.notification.domain.event.NotificationRaised;
import com.tarimatwasi.quipu.payment.domain.event.PaymentCorrected;
import com.tarimatwasi.quipu.payment.domain.event.PaymentRegistered;
import com.tarimatwasi.quipu.payment.domain.event.PaymentResolved;
import com.tarimatwasi.quipu.payment.domain.event.PaymentResubmitted;
import com.tarimatwasi.quipu.payment.domain.event.PaymentSubmitted;
import com.tarimatwasi.quipu.payment.domain.event.RefundCreated;
import com.tarimatwasi.quipu.shared.events.DomainEvent;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Live invalidations of the open streams, derived from the domain events. It listens inside the
 * transaction that publishes them and only appends to the transaction buffer of {@link
 * ChangeStreamRegistry}, which delivers the whole transaction as one batch after the commit,
 * security closures first; a rollback delivers nothing. In memory, never an outbox listener: an
 * invalidation replayed after a restart is worthless. The few reads it makes (the fingerprint of a
 * revoked session, the readers of a relabeled environment) run in the publishing transaction, so
 * their failure fails the operation. That is on purpose: catching it would not save the transaction
 * (PostgreSQL aborts it on a failed statement), and a security closure must not commit without the
 * streams it closes.
 */
@Component
public class DomainEventInvalidations {
  private final ChangeStreamRegistry streams;
  private final SessionFingerprintUseCase sessions;
  private final ManageEnvironmentsUseCase environments;

  public DomainEventInvalidations(
      ChangeStreamRegistry streams,
      SessionFingerprintUseCase sessions,
      ManageEnvironmentsUseCase environments) {
    this.streams = streams;
    this.sessions = sessions;
    this.environments = environments;
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
      // Labels also appear in historical payment and contract details: their readers refresh.
      case EnvironmentRelabeled e ->
          streams.changed(
              new Change(
                  Kind.DIRECTORY,
                  null,
                  false,
                  null,
                  e.environmentId(),
                  environments.labelReadersOf(e.environmentId())));
      default -> changesOf(event).forEach(streams::changed);
    }
  }

  /** The resource groups a fact touches; empty for facts no open screen shows. */
  static List<Change> changesOf(DomainEvent event) {
    if (event instanceof BusinessDayStarted) {
      return List.of(new Change(Kind.DAY, null, false, null, null));
    }
    List<Change> found = finance(event);
    if (found.isEmpty()) {
      found = occupancy(event);
    }
    return found.isEmpty() ? guests(event) : found;
  }

  /** Payments, refunds, expenses and fines. */
  private static List<Change> finance(DomainEvent event) {
    return switch (event) {
      case ExpenseCreated e -> List.of(administrative(Kind.EXPENSE));
      case ExpenseUpdated e -> List.of(administrative(Kind.EXPENSE));
      case ExpenseDeleted e -> List.of(administrative(Kind.EXPENSE));
      case FineApplied e -> List.of(new Change(Kind.FINE, e.guestId(), false, null, null));
      case FineDeleted e -> List.of(new Change(Kind.FINE, e.guestId(), false, null, null));
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

  /** Contracts, stays and environments. */
  private static List<Change> occupancy(DomainEvent event) {
    return switch (event) {
      case ContractSigned e -> List.of(contract(e.guestId(), e.environmentId()));
      case ContractTerminated e -> List.of(contract(e.guestId(), e.environmentId()));
      case StayCreated e ->
          List.of(new Change(Kind.STAY, e.guestId(), false, null, e.environmentId()));
      case StayUpdated e ->
          List.of(new Change(Kind.STAY, e.guestId(), false, null, e.environmentId()));
      case EnvironmentCreated e -> List.of(environment(e.environmentId()));
      case EnvironmentUpdated e -> List.of(environment(e.environmentId()));
      case EnvironmentDeactivated e -> List.of(environment(e.environmentId()));
      case EnvironmentReactivated e -> List.of(environment(e.environmentId()));
      default -> List.of();
    };
  }

  /** Guests, their accounts, onboarding, documents and bell. */
  private static List<Change> guests(DomainEvent event) {
    return switch (event) {
      case NotificationRaised e ->
          List.of(
              new Change(
                  Kind.NOTIFICATION,
                  e.forAdministrators() ? null : e.guestId(),
                  false,
                  null,
                  null));
      case GuestAccountEmailChanged e -> List.of(guest(Kind.PROFILE, e.guestId()));
      case GuestIdentityCorrected e -> List.of(guest(Kind.DIRECTORY, e.guestId()));
      case GuestDeactivated e -> List.of(guest(Kind.GUEST, e.guestId()));
      case GuestReactivated e -> List.of(guest(Kind.GUEST, e.guestId()));
      case GuestAccessUsed e -> List.of(guest(Kind.GUEST_RECORD, e.guestId()));
      case ProfileUpdated e ->
          List.of(guest(e.renamed() ? Kind.DIRECTORY : Kind.PROFILE, e.guestId()));
      case GuestRegistered e -> List.of(guest(Kind.GUEST_RECORD, e.guestId()));
      case GuestAccessIssued e -> List.of(guest(Kind.GUEST_RECORD, e.guestId()));
      // A new name also shows in the history: DIRECTORY refreshes every visible resource.
      case PersonalDataSaved e ->
          List.of(guest(e.renamed() ? Kind.DIRECTORY : Kind.GUEST_RECORD, e.guestId()));
      case DocumentsStepFinished e -> List.of(guest(Kind.GUEST_RECORD, e.guestId()));
      case OnboardingCompleted e -> List.of(guest(Kind.GUEST_RECORD, e.guestId()));
      case GuestDocumentUploaded e -> List.of(guest(Kind.DOCUMENT, e.guestId()));
      case GuestDocumentDeletionRequested e -> List.of(guest(Kind.DOCUMENT, e.guestId()));
      case GuestDocumentDeleted e -> List.of(guest(Kind.DOCUMENT, e.guestId()));
      case GuestDocumentReplaced e -> List.of(guest(Kind.DOCUMENT, e.guestId()));
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

  private static Change guest(Kind kind, Long guestId) {
    return new Change(kind, guestId, false, null, null);
  }

  private static Change environment(Long environmentId) {
    return new Change(Kind.ENVIRONMENT, null, false, null, environmentId);
  }

  private static Change contract(Long guestId, Long environmentId) {
    return new Change(Kind.CONTRACT, guestId, true, null, environmentId);
  }

  private static Change administrative(Kind kind) {
    return new Change(kind, null, false, null, null);
  }
}
