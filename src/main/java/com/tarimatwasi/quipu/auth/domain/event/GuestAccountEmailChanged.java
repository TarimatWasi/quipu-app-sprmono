package com.tarimatwasi.quipu.auth.domain.event;

import com.tarimatwasi.quipu.shared.events.DomainEvent;
import com.tarimatwasi.quipu.shared.events.EventMetadata;

/** The email of the account of a guest changed (TAR-229). */
public record GuestAccountEmailChanged(EventMetadata metadata, Long guestId)
    implements DomainEvent {

  @Override
  public String subject() {
    return "guest/" + guestId;
  }
}
