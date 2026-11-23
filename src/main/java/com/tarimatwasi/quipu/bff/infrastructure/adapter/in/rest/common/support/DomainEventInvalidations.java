package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.support;

import com.tarimatwasi.quipu.contract.domain.event.ContractSigned;
import com.tarimatwasi.quipu.contract.domain.event.ContractTerminated;
import com.tarimatwasi.quipu.expense.domain.event.ExpenseCreated;
import com.tarimatwasi.quipu.expense.domain.event.ExpenseDeleted;
import com.tarimatwasi.quipu.expense.domain.event.ExpenseUpdated;
import com.tarimatwasi.quipu.fine.domain.event.FineApplied;
import com.tarimatwasi.quipu.fine.domain.event.FineDeleted;
import com.tarimatwasi.quipu.notification.domain.event.NotificationRaised;
import com.tarimatwasi.quipu.shared.changes.ChangeSignals.Change;
import com.tarimatwasi.quipu.shared.changes.ChangeSignals.Kind;
import com.tarimatwasi.quipu.shared.events.DomainEvent;
import java.util.List;
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

  public DomainEventInvalidations(ChangeStreamRegistry streams) {
    this.streams = streams;
  }

  @EventListener
  public void on(DomainEvent event) {
    changesOf(event).forEach(streams::changed);
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
      default -> List.of();
    };
  }

  private static Change contract(Long guestId, Long environmentId) {
    return new Change(Kind.CONTRACT, guestId, true, null, environmentId);
  }

  private static Change administrative(Kind kind) {
    return new Change(kind, null, false, null, null);
  }
}
