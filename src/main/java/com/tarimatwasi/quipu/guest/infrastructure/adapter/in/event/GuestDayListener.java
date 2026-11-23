package com.tarimatwasi.quipu.guest.infrastructure.adapter.in.event;

import com.tarimatwasi.quipu.calendar.domain.event.BusinessDayStarted;
import com.tarimatwasi.quipu.guest.application.port.in.GuestTimelineUseCase;
import com.tarimatwasi.quipu.shared.access.SystemActor;
import com.tarimatwasi.quipu.shared.events.delivery.DurableListener;
import org.springframework.stereotype.Component;

/** Each business day checks the stays about to end and the accesses expired unused. */
@Component
public class GuestDayListener {
  private final GuestTimelineUseCase timeline;

  public GuestDayListener(GuestTimelineUseCase timeline) {
    this.timeline = timeline;
  }

  @DurableListener
  public void on(BusinessDayStarted day) {
    SystemActor.run(() -> timeline.sweep(day.day()));
  }
}
