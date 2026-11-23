package com.tarimatwasi.quipu.guest.domain.event;

import com.tarimatwasi.quipu.shared.events.DomainEvent;
import com.tarimatwasi.quipu.shared.events.EventMetadata;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/** A one-time stay in course ends within two days; stated once per stay and end date. */
public record StayEnding(
    EventMetadata metadata,
    Long stayId,
    Long guestId,
    @Nullable Long environmentId,
    LocalDate endDate)
    implements DomainEvent {

  @Override
  public String subject() {
    return "stay/" + stayId;
  }
}
