package com.tarimatwasi.quipu.calendar.infrastructure.adapter.in.traffic;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.tarimatwasi.quipu.calendar.application.port.in.StartBusinessDayUseCase;
import com.tarimatwasi.quipu.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class DayStartTriggerTest {

  private static final LocalDate DAY = LocalDate.of(2026, 10, 14);

  private final StartBusinessDayUseCase calendar = mock(StartBusinessDayUseCase.class);
  private final MutableClock clock = new MutableClock(Instant.parse("2026-10-14T12:00:00Z"));

  private DayStartTrigger trigger(boolean enabled) {
    return new DayStartTrigger(calendar, clock, new CalendarProperties(enabled));
  }

  /** The startup and the first request of each day start it; the rest of the day does not. */
  @Test
  void eachDayStartsOnceWithTheTrafficItself() {
    DayStartTrigger trigger = trigger(true);

    trigger.atStartup();
    trigger.afterARequest();
    clock.advance(Duration.ofDays(1));
    trigger.afterARequest();
    trigger.afterARequest();

    verify(calendar).start(DAY);
    verify(calendar).start(DAY.plusDays(1));
    verify(calendar, times(2)).start(any());
  }

  /** A day that could not start is left for the next request, which starts it. */
  @Test
  void aDayThatFailedToStartIsStartedByTheNextRequest() {
    DayStartTrigger trigger = trigger(true);
    doThrow(new DataAccessResourceFailureException("down")).doNothing().when(calendar).start(DAY);

    trigger.afterARequest();
    trigger.afterARequest();
    trigger.afterARequest();

    verify(calendar, times(2)).start(DAY);
  }

  /** Off (the tests start their days themselves): the traffic starts nothing. */
  @Test
  void offItStartsNothing() {
    DayStartTrigger trigger = trigger(false);

    trigger.atStartup();
    trigger.afterARequest();

    verify(calendar, never()).start(any());
  }
}
