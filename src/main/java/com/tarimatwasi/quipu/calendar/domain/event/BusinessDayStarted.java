package com.tarimatwasi.quipu.calendar.domain.event;

import com.tarimatwasi.quipu.shared.events.DomainEvent;
import com.tarimatwasi.quipu.shared.events.EventMetadata;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * A business day of Lima started: what time alone makes true (a due date reached, a period overdue,
 * a contract about to end) is checked again. Stated once per day; its id is the day itself.
 */
public record BusinessDayStarted(EventMetadata metadata, LocalDate day) implements DomainEvent {

  @Override
  public String subject() {
    return "business-day/" + day.format(DateTimeFormatter.BASIC_ISO_DATE);
  }
}
