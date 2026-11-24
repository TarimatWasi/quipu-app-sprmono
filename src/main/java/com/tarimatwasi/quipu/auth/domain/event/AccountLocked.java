package com.tarimatwasi.quipu.auth.domain.event;

import com.tarimatwasi.quipu.shared.events.DomainEvent;
import com.tarimatwasi.quipu.shared.events.EventMetadata;

/** Too many failed attempts locked an account for a while (RF-08). */
public record AccountLocked(EventMetadata metadata, Long userId) implements DomainEvent {

  @Override
  public String subject() {
    return "user/" + userId;
  }
}
