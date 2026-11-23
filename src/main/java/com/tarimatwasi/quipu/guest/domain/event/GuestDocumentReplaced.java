package com.tarimatwasi.quipu.guest.domain.event;

import com.tarimatwasi.quipu.shared.events.DomainEvent;
import com.tarimatwasi.quipu.shared.events.EventMetadata;

/** An administrator replaced a document of a guest, with its evidence (RN-37). */
public record GuestDocumentReplaced(
    EventMetadata metadata, Long guestId, Long documentId, Long replacementId, Long requestedBy)
    implements DomainEvent {

  @Override
  public String subject() {
    return "guest-document/" + documentId;
  }
}
