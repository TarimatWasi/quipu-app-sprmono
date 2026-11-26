package com.tarimatwasi.quipu.contract.infrastructure.adapter.in.event;

import com.tarimatwasi.quipu.calendar.domain.event.BusinessDayStarted;
import com.tarimatwasi.quipu.contract.application.port.in.ContractTimelineUseCase;
import com.tarimatwasi.quipu.shared.access.SystemActor;
import com.tarimatwasi.quipu.shared.events.delivery.DurableListener;
import org.springframework.stereotype.Component;

/** Each business day checks the contracts about to end (durable: retried until it completes). */
@Component
public class ContractDayListener {
  private final ContractTimelineUseCase timeline;

  public ContractDayListener(ContractTimelineUseCase timeline) {
    this.timeline = timeline;
  }

  @DurableListener
  public void on(BusinessDayStarted day) {
    SystemActor.run(() -> timeline.sweep(day.day()));
  }
}
