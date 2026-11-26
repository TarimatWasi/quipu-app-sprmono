package com.tarimatwasi.quipu.shared.events.publishing;

import org.springframework.core.Ordered;

/**
 * Order of the advisors around a use case: security (Spring's default) outside, then the
 * transaction, then the publication of events innermost, so that the events join the transaction.
 */
public final class EventOrdering {

  /** The transaction advisor: just outside the publication. */
  public static final int TRANSACTION = Ordered.LOWEST_PRECEDENCE - 10;

  /** The publication of events: the innermost advisor. */
  public static final int PUBLISH = Ordered.LOWEST_PRECEDENCE;

  private EventOrdering() {}
}
