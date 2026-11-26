package com.tarimatwasi.quipu.shared.events.publishing;

import com.tarimatwasi.quipu.shared.events.DomainEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Resubmits outbox publications that never completed: a listener that failed, or one the shared
 * executor rejected at its limit (those stay PUBLISHED, not FAILED). Without a scheduled task
 * (BE-SPR-CON-01): the traffic itself drives it, at most once a minute, after the commit of a
 * transaction that published an event, as the purge of documents does with its rounds. The restart
 * republishes the rest. Every durable listener is idempotent by event id, so resubmitting one that
 * was only slow repeats nothing.
 */
@Component
public class OutboxRecovery {
  private static final Logger LOGGER = LoggerFactory.getLogger(OutboxRecovery.class);

  /** At most one round per this interval. */
  static final Duration EVERY = Duration.ofMinutes(1);

  /**
   * Only publications older than this: a younger one may still be running or waiting its turn. A
   * resubmission repeats the delivery (Modulith resubmits every incomplete one, queued ones too),
   * so this outlasts a full queue of the delivery executor: 1000 waiting on 4 threads at a generous
   * 2 s each drain in about 8 minutes. A delivery the executor rejected is recovered after it.
   */
  static final Duration STALE = Duration.ofMinutes(10);

  private final IncompleteEventPublications incomplete;
  private final Clock clock;
  private final AtomicReference<Instant> next = new AtomicReference<>(Instant.MIN);

  public OutboxRecovery(IncompleteEventPublications incomplete, Clock clock) {
    this.incomplete = incomplete;
    this.clock = clock;
  }

  @TransactionalEventListener
  public void afterCommit(DomainEvent event) {
    Instant now = clock.instant();
    // In one atomic step: a round that is due moves the next one forward, so exactly one thread
    // finds it due (the one that moved it); the others, and any before the time, go on at once.
    Instant due =
        next.getAndAccumulate(now, (at, when) -> when.isBefore(at) ? at : when.plus(EVERY));
    if (now.isBefore(due)) {
      return;
    }
    try {
      incomplete.resubmitIncompletePublicationsOlderThan(STALE);
    } catch (DataAccessException e) {
      // The committed business result stands; the next round or the restart tries again.
      LOGGER.warn("Outbox recovery round failed: {}", e.getClass().getSimpleName());
    }
  }
}
