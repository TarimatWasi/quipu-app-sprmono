package com.tarimatwasi.quipu.guest.domain.event;

import com.tarimatwasi.quipu.shared.events.DomainEvent;
import com.tarimatwasi.quipu.shared.events.EventMetadata;
import java.time.Instant;

/**
 * The access handed to a guest pending activation expired unused: it cannot activate its account
 * until an administrator hands a new one. Stated once per guest and expiry.
 */
public record AccessExpired(EventMetadata metadata, Long guestId, Instant expiredAt)
    implements DomainEvent {

  @Override
  public String subject() {
    return "guest/" + guestId;
  }
}
