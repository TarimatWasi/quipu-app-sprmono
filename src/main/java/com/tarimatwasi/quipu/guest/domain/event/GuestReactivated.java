package com.tarimatwasi.quipu.guest.domain.event;

import com.tarimatwasi.quipu.shared.events.DomainEvent;
import com.tarimatwasi.quipu.shared.events.EventMetadata;

/** An administrator reactivated a guest (RF-13, CU-28). */
public record GuestReactivated(EventMetadata metadata, Long guestId) implements DomainEvent {

  @Override
  public String subject() {
    return "guest/" + guestId;
  }
}
