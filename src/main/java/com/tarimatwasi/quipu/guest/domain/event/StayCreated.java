package com.tarimatwasi.quipu.guest.domain.event;

import com.tarimatwasi.quipu.shared.events.DomainEvent;
import com.tarimatwasi.quipu.shared.events.EventMetadata;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * An administrator registered a one-time stay of a temporary guest (RN-20).
 *
 * @param environmentId the environment it occupies now; null for a stay without one
 */
public record StayCreated(
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
