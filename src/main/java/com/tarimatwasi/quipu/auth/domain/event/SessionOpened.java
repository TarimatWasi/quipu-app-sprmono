package com.tarimatwasi.quipu.auth.domain.event;

import com.tarimatwasi.quipu.shared.events.DomainEvent;
import com.tarimatwasi.quipu.shared.events.EventMetadata;

/**
 * An account opened a session (a login or an activation, TAR-228): the trail of who entered and
 * when. Temporary sessions come from a temporary password (RF-12). The sid never travels: the
 * session is named by its internal id, as {@link SessionRevoked} names it when it ends.
 */
public record SessionOpened(EventMetadata metadata, Long sessionId, Long userId, boolean temporary)
    implements DomainEvent {

  @Override
  public String subject() {
    return "session/" + sessionId;
  }
}
