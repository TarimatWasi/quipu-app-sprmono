package com.tarimatwasi.quipu.auth.infrastructure.adapter.in.event;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.auth.application.port.in.SessionRetentionUseCase;
import com.tarimatwasi.quipu.calendar.domain.event.BusinessDayStarted;
import com.tarimatwasi.quipu.shared.events.EventMetadata;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SessionDayListenerTest {

  private final SessionRetentionUseCase retention = mock(SessionRetentionUseCase.class);
  private final SessionDayListener listener = new SessionDayListener(retention);

  @Test
  void fullBatchesGoOnUntilAShorterOne() {
    when(retention.purgeEnded())
        .thenReturn(SessionRetentionUseCase.BATCH, SessionRetentionUseCase.BATCH, 7);

    listener.on(day());

    verify(retention, times(3)).purgeEnded();
  }

  @Test
  void aDayStopsAtItsMostBatches() {
    when(retention.purgeEnded()).thenReturn(SessionRetentionUseCase.BATCH);

    listener.on(day());

    verify(retention, times(SessionDayListener.MAX_BATCHES)).purgeEnded();
  }

  private static BusinessDayStarted day() {
    return new BusinessDayStarted(
        new EventMetadata(UUID.randomUUID(), Instant.now(), null), LocalDate.of(2026, 11, 23));
  }
}
