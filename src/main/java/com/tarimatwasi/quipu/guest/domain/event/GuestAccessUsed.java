package com.tarimatwasi.quipu.guest.domain.event;

import com.tarimatwasi.quipu.shared.events.DomainEvent;
import com.tarimatwasi.quipu.shared.events.EventMetadata;

/** A guest pending activation chose its password with an access: its onboarding starts (D-30). */
public record GuestAccessUsed(EventMetadata metadata, Long guestId) implements DomainEvent {

  @Override
  public String subject() {
    return "guest/" + guestId;
  }
}
