package com.tarimatwasi.quipu.calendar.application.service;

import com.tarimatwasi.quipu.calendar.application.port.in.StartBusinessDayUseCase;
import com.tarimatwasi.quipu.calendar.domain.event.BusinessDayStarted;
import com.tarimatwasi.quipu.shared.events.PublishEvent;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A fact of time writes nothing of its own: this method is the point where it is stated. The id is
 * the day, so a restart or a second instance that states it again changes nothing downstream.
 */
@Service
public class CalendarService implements StartBusinessDayUseCase {

  @Override
  @Transactional
  @PublishEvent(
      type = BusinessDayStarted.class,
      id = "#day.toString()",
      args = {"#day"})
  public void start(LocalDate day) {
    // The fact is the whole change: the outbox keeps it with this transaction.
  }
}
