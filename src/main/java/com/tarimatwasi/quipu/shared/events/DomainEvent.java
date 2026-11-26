package com.tarimatwasi.quipu.shared.events;

import org.jspecify.annotations.Nullable;

/**
 * A confirmed business fact, published by {@link PublishEvent} inside the transaction that made it
 * true. Implementations are records whose first component is the {@link EventMetadata}; the other
 * components are identifiers and values only (no free text, no personal data), because the event is
 * stored in the outbox and the audit trail (Ley 29733).
 *
 * <p>Listeners own every decision about the fact: who is notified, what is refreshed, what is
 * audited. The service that publishes it only states what happened.
 */
public interface DomainEvent {

  /** Who, when and which occurrence; captured on the request thread. */
  EventMetadata metadata();

  /** CloudEvents {@code subject}: {@code <kind>/<internal id>}, such as {@code payment/42}. */
  String subject();

  /** The guest the fact is about, if any: the audit trail answers ARCO requests by guest. */
  default @Nullable Long guestId() {
    return null;
  }

  /** Schema version of the record; raise it on any incompatible change of its components. */
  default int version() {
    return 1;
  }
}
