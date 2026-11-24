package com.tarimatwasi.quipu.auth.infrastructure.adapter.in.event;

import com.tarimatwasi.quipu.auth.application.port.in.SessionRetentionUseCase;
import com.tarimatwasi.quipu.calendar.domain.event.BusinessDayStarted;
import com.tarimatwasi.quipu.shared.access.SystemActor;
import com.tarimatwasi.quipu.shared.events.delivery.DurableListener;
import org.springframework.stereotype.Component;

/**
 * Each business day removes the sessions that ended (durable: retried until it completes). In
 * batches, each its own transaction, so none nears the limit of a statement; a day leaves the rest,
 * if any, to the next one.
 */
@Component
public class SessionDayListener {

  /** 500 sessions a batch: up to 50 000 a day. */
  static final int MAX_BATCHES = 100;

  private final SessionRetentionUseCase retention;

  public SessionDayListener(SessionRetentionUseCase retention) {
    this.retention = retention;
  }

  @DurableListener
  public void on(BusinessDayStarted day) {
    SystemActor.run(
        () -> {
          int batches = 0;
          // A full batch may leave more behind; a shorter one was the last.
          while (batches < MAX_BATCHES && retention.purgeEnded() == SessionRetentionUseCase.BATCH) {
            batches++;
          }
        });
  }
}
