package com.tarimatwasi.quipu.shared.events.publishing;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.tarimatwasi.quipu.shared.events.DomainEvent;
import com.tarimatwasi.quipu.shared.events.EventMetadata;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.modulith.events.IncompleteEventPublications;

class OutboxRecoveryTest {
  private static final Instant START = Instant.parse("2026-11-22T15:00:00Z");

  private final IncompleteEventPublications incomplete = mock(IncompleteEventPublications.class);
  private final MovingClock clock = new MovingClock(START);
  private final OutboxRecovery recovery = new OutboxRecovery(incomplete, clock);

  record Happened(EventMetadata metadata) implements DomainEvent {
    @Override
    public String subject() {
      return "test/1";
    }
  }

  private static Happened event() {
    return new Happened(new EventMetadata(UUID.randomUUID(), START, null));
  }

  @Test
  void firstEvent_runsARoundForStalePublications() {
    recovery.afterCommit(event());

    verify(incomplete).resubmitIncompletePublicationsOlderThan(OutboxRecovery.STALE);
  }

  @Test
  void rounds_runAtMostOncePerInterval() {
    recovery.afterCommit(event());
    recovery.afterCommit(event());
    clock.now = START.plus(OutboxRecovery.EVERY).minusSeconds(1);
    recovery.afterCommit(event());
    clock.now = START.plus(OutboxRecovery.EVERY);
    recovery.afterCommit(event());

    verify(incomplete, times(2)).resubmitIncompletePublicationsOlderThan(OutboxRecovery.STALE);
  }

  @Test
  void failedRound_neverReachesTheCommittedRequest() {
    doThrow(new DataAccessResourceFailureException("down"))
        .when(incomplete)
        .resubmitIncompletePublicationsOlderThan(OutboxRecovery.STALE);

    recovery.afterCommit(event());

    verify(incomplete).resubmitIncompletePublicationsOlderThan(OutboxRecovery.STALE);
  }

  /** A clock the test moves. */
  private static final class MovingClock extends Clock {
    private Instant now;

    private MovingClock(Instant now) {
      this.now = now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
