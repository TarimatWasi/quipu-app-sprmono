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
import org.springframework.transaction.TransactionException;
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

  /**
   * One thread per day states it; a failure leaves the day for the next request. A database that
   * does not answer (no connection for the transaction, a statement cut short) is expected at the
   * edge: it is logged and swallowed, so neither the request nor the startup fails. Anything else
   * still undoes the mark of the day before it propagates.
   */
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
    boolean done = false;
    try {
      calendar.start(today);
      done = true;
    } catch (DataAccessException | TransactionException e) {
      LOGGER.warn("The business day could not start yet: {}", e.getClass().getSimpleName());
    } finally {
      if (!done) {
        started.compareAndSet(today, last);
      }
    }
  }
}
