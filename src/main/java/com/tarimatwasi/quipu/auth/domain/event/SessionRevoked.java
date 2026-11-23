package com.tarimatwasi.quipu.auth.domain.event;

import com.tarimatwasi.quipu.shared.events.DomainEvent;
import com.tarimatwasi.quipu.shared.events.EventMetadata;

/** A session of an account was closed on the server; its live streams close too. */
public record SessionRevoked(EventMetadata metadata, Long sessionId, Long userId, SessionEnd cause)
    implements DomainEvent {

  @Override
  public String subject() {
    return "session/" + sessionId;
  }
}
