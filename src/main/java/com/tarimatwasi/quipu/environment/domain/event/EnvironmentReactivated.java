package com.tarimatwasi.quipu.environment.domain.event;

import com.tarimatwasi.quipu.shared.events.DomainEvent;
import com.tarimatwasi.quipu.shared.events.EventMetadata;

/** An administrator brought an environment back to the operational listings. */
public record EnvironmentReactivated(EventMetadata metadata, Long environmentId)
    implements DomainEvent {

  @Override
  public String subject() {
    return "environment/" + environmentId;
  }
}
