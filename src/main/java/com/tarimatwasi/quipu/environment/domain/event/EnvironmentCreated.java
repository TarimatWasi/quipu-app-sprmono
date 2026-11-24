package com.tarimatwasi.quipu.environment.domain.event;

import com.tarimatwasi.quipu.shared.events.DomainEvent;
import com.tarimatwasi.quipu.shared.events.EventMetadata;

/** An administrator created an environment. */
public record EnvironmentCreated(EventMetadata metadata, Long environmentId)
    implements DomainEvent {

  @Override
  public String subject() {
    return "environment/" + environmentId;
  }
}
