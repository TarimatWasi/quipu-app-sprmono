package com.tarimatwasi.quipu.guest.domain.event;

import com.tarimatwasi.quipu.shared.events.DomainEvent;
import com.tarimatwasi.quipu.shared.events.EventMetadata;

/** A document of a guest was deleted, with its evidence (RN-37). */
public record GuestDocumentDeleted(
    EventMetadata metadata, Long guestId, Long documentId, Long requestedBy)
    implements DomainEvent {

  @Override
  public String subject() {
    return "guest-document/" + documentId;
  }
}
