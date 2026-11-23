package com.tarimatwasi.quipu.guest.domain.event;

import com.tarimatwasi.quipu.shared.events.DomainEvent;
import com.tarimatwasi.quipu.shared.events.EventMetadata;

/** The guest finished the documents step of its onboarding (RN-33). */
public record DocumentsStepFinished(EventMetadata metadata, Long guestId) implements DomainEvent {

  @Override
  public String subject() {
    return "guest/" + guestId;
  }
}
