package com.tarimatwasi.quipu.guest.domain.event;

import com.tarimatwasi.quipu.shared.events.DomainEvent;
import com.tarimatwasi.quipu.shared.events.EventMetadata;

/** An administrator asked to delete a document of a guest (RN-37); it shows as pending. */
public record GuestDocumentDeletionRequested(
    EventMetadata metadata, Long guestId, Long documentId, Long requestedBy)
    implements DomainEvent {

  @Override
  public String subject() {
    return "guest-document/" + documentId;
  }
}
