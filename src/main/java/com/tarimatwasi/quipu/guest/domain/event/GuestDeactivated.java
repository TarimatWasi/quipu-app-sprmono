package com.tarimatwasi.quipu.guest.domain.event;

import com.tarimatwasi.quipu.shared.events.DomainEvent;
import com.tarimatwasi.quipu.shared.events.EventMetadata;

/** An administrator deactivated a guest (RF-13, RN-12). */
public record GuestDeactivated(EventMetadata metadata, Long guestId) implements DomainEvent {

  @Override
  public String subject() {
    return "guest/" + guestId;
  }
}
