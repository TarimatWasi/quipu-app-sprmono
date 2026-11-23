package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.support;

import com.tarimatwasi.quipu.contract.domain.event.ContractSigned;
import com.tarimatwasi.quipu.contract.domain.event.ContractTerminated;
import com.tarimatwasi.quipu.expense.domain.event.ExpenseCreated;
import com.tarimatwasi.quipu.expense.domain.event.ExpenseDeleted;
import com.tarimatwasi.quipu.expense.domain.event.ExpenseUpdated;
import com.tarimatwasi.quipu.fine.domain.event.FineApplied;
import com.tarimatwasi.quipu.fine.domain.event.FineDeleted;
import com.tarimatwasi.quipu.shared.changes.ChangeSignals.Change;
import com.tarimatwasi.quipu.shared.changes.ChangeSignals.Kind;
import com.tarimatwasi.quipu.shared.events.DomainEvent;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Live invalidations of the open streams, derived from the domain events after the commit. In
 * memory and best effort: not an outbox listener (an invalidation replayed after a restart is
 * worthless, the epoch changed and every client resynchronizes). The routing of each resource group
 * to the keys of the contract stays in {@link ChangeStreamRegistry}.
 */
@Component
public class DomainEventInvalidations {
  private final ChangeStreamRegistry streams;

  public DomainEventInvalidations(ChangeStreamRegistry streams) {
    this.streams = streams;
  }

  @TransactionalEventListener
  public void on(DomainEvent event) {
    List<Change> changes = changesOf(event);
    if (!changes.isEmpty()) {
      streams.committed(changes);
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
