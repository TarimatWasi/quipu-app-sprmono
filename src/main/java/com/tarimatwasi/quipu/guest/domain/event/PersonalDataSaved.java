package com.tarimatwasi.quipu.guest.domain.event;

import com.tarimatwasi.quipu.shared.events.DomainEvent;
import com.tarimatwasi.quipu.shared.events.EventMetadata;

/** The guest saved its personal data while onboarding; renamed when its name changed. */
public record PersonalDataSaved(EventMetadata metadata, Long guestId, boolean renamed)
    implements DomainEvent {

  @Override
  public String subject() {
    return "guest/" + guestId;
  }
}
