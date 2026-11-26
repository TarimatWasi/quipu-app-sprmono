package com.tarimatwasi.quipu.fine.domain.event;

import com.tarimatwasi.quipu.shared.events.DomainEvent;
import com.tarimatwasi.quipu.shared.events.EventMetadata;
import java.math.BigDecimal;
import java.time.YearMonth;

/** An administrator fined a guest for a month. */
public record FineApplied(
    EventMetadata metadata, Long fineId, Long guestId, BigDecimal amount, YearMonth month)
    implements DomainEvent {

  @Override
  public String subject() {
    return "fine/" + fineId;
  }
}
