package com.tarimatwasi.quipu.payment.infrastructure.adapter.in.event;

import com.tarimatwasi.quipu.calendar.domain.event.BusinessDayStarted;
import com.tarimatwasi.quipu.payment.application.port.in.PaymentTimelineUseCase;
import com.tarimatwasi.quipu.shared.access.SystemActor;
import com.tarimatwasi.quipu.shared.events.delivery.DurableListener;
import org.springframework.stereotype.Component;

/** Each business day checks the charges again (durable: retried until it completes). */
@Component
public class PaymentDayListener {
  private final PaymentTimelineUseCase timeline;

  public PaymentDayListener(PaymentTimelineUseCase timeline) {
    this.timeline = timeline;
  }

  @DurableListener
  public void on(BusinessDayStarted day) {
    SystemActor.run(() -> timeline.sweep(day.day()));
  }
}
