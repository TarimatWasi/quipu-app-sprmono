package com.tarimatwasi.quipu.guest.domain.event;

import com.tarimatwasi.quipu.shared.events.DomainEvent;
import com.tarimatwasi.quipu.shared.events.EventMetadata;

/** The guest uploaded an identity document. */
public record GuestDocumentUploaded(EventMetadata metadata, Long guestId, Long documentId)
    implements DomainEvent {

  @Override
  public String subject() {
    return "guest-document/" + documentId;
  }
}
