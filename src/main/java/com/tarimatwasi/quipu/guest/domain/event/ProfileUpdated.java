package com.tarimatwasi.quipu.guest.domain.event;

import com.tarimatwasi.quipu.shared.events.DomainEvent;
import com.tarimatwasi.quipu.shared.events.EventMetadata;

/**
 * A guest edited its own profile; renamed when its name changed, which also shows in its history.
 */
public record ProfileUpdated(EventMetadata metadata, Long guestId, boolean renamed)
    implements DomainEvent {

  @Override
  public String subject() {
    return "guest/" + guestId;
  }
}
