package com.tarimatwasi.quipu.guest.domain.event;

import com.tarimatwasi.quipu.shared.events.DomainEvent;
import com.tarimatwasi.quipu.shared.events.EventMetadata;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * An administrator changed the dates, the amount or the environment of a stay.
 *
 * @param environmentId the environment it occupies now; null for a stay without one
 */
public record StayUpdated(
    EventMetadata metadata,
    Long stayId,
    Long guestId,
    @Nullable Long environmentId,
    LocalDate startDate,
    LocalDate endDate,
    BigDecimal agreedAmount)
    implements DomainEvent {

  @Override
  public String subject() {
    return "stay/" + stayId;
  }
}
