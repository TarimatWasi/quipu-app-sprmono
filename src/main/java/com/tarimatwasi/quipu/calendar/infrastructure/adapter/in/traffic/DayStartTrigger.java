package com.tarimatwasi.quipu.calendar.infrastructure.adapter.in.traffic;

import com.tarimatwasi.quipu.calendar.application.port.in.StartBusinessDayUseCase;
import java.time.Clock;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.web.context.support.ServletRequestHandledEvent;

/**
 * Starts the business day with the traffic itself (BE-SPR-CON-01: no scheduled task): at startup
 * and on the first request handled each day of Lima. A day without any request starts with the next
 * one, and what it makes true is found then, because the modules check states, not dates.
 */
@Component
public class DayStartTrigger {
  private static final Logger LOGGER = LoggerFactory.getLogger(DayStartTrigger.class);

  private final StartBusinessDayUseCase calendar;
  private final Clock clock;
  private final boolean enabled;
  private final AtomicReference<@Nullable LocalDate> started = new AtomicReference<>();

  public DayStartTrigger(
      StartBusinessDayUseCase calendar, Clock clock, CalendarProperties properties) {
    this.calendar = calendar;
    this.clock = clock;
    this.enabled = properties.trafficTrigger();
  }

  @EventListener(ApplicationReadyEvent.class)
  public void atStartup() {
    startIfNew();
  }

  @EventListener(ServletRequestHandledEvent.class)
  public void afterARequest() {
    startIfNew();
  }

  /** One thread per day states it; a failure leaves the day for the next request. */
  void startIfNew() {
    if (!enabled) {
      return;
    }
    LocalDate today = LocalDate.now(clock);
    // In one atomic step: only the thread that moves the day forward finds a different one.
    LocalDate last = started.getAndSet(today);
    if (today.equals(last)) {
      return;
    }
    try {
      calendar.start(today);
    } catch (DataAccessException e) {
      started.compareAndSet(today, last);
      LOGGER.warn("The business day could not start yet: {}", e.getClass().getSimpleName());
    }
  }
}
