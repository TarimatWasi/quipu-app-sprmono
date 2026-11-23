package com.tarimatwasi.quipu.auth.domain.event;

import com.tarimatwasi.quipu.shared.events.DomainEvent;
import com.tarimatwasi.quipu.shared.events.EventMetadata;

/** An administrator created another administrator account (RF-22); the actor is the creator. */
public record AdministratorCreated(EventMetadata metadata, Long administratorId)
    implements DomainEvent {

  @Override
  public String subject() {
    return "user/" + administratorId;
  }
}
