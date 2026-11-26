package com.tarimatwasi.quipu.guest.domain.event;

import com.tarimatwasi.quipu.shared.events.DomainEvent;
import com.tarimatwasi.quipu.shared.events.EventMetadata;

/** An administrator corrected the identity document of a guest: its labels change everywhere. */
public record GuestIdentityCorrected(EventMetadata metadata, Long guestId) implements DomainEvent {

  @Override
  public String subject() {
    return "guest/" + guestId;
  }
}
