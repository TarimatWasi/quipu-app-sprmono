package com.tarimatwasi.quipu.fine.domain.event;

import com.tarimatwasi.quipu.shared.events.DomainEvent;
import com.tarimatwasi.quipu.shared.events.EventMetadata;

/** An administrator deleted a fine of a guest. */
public record FineDeleted(EventMetadata metadata, Long fineId, Long guestId)
    implements DomainEvent {

  @Override
  public String subject() {
    return "fine/" + fineId;
  }
}
