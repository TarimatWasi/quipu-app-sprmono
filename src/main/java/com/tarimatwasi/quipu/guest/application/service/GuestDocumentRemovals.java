package com.tarimatwasi.quipu.guest.application.service;

import com.tarimatwasi.quipu.guest.application.port.out.GuestDocumentActionRepositoryPort;
import com.tarimatwasi.quipu.guest.application.port.out.GuestRepositoryPort;
import com.tarimatwasi.quipu.guest.domain.GuestDocument;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.Kind;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.PendingDeletion;
import com.tarimatwasi.quipu.guest.domain.event.GuestDocumentDeleted;
import com.tarimatwasi.quipu.guest.domain.event.GuestDocumentDeletionRequested;
import com.tarimatwasi.quipu.shared.events.PublishEvent;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two database steps of deleting a document (RN-37), each in a transaction of its own and with
 * the fact it states: the deletion asked for, committed before its object leaves the store; and the
 * deletion finished, with its evidence. Apart from {@link GuestDocumentPurger} so that its rounds
 * go through this proxy too; the event names who asked, since a round has no user.
 */
@Service
public class GuestDocumentRemovals {
  private final GuestRepositoryPort guests;
  private final GuestDocumentActionRepositoryPort records;

  GuestDocumentRemovals(GuestRepositoryPort guests, GuestDocumentActionRepositoryPort records) {
    this.guests = guests;
    this.records = records;
  }

  /** The deletion as recorded, and whether this call recorded it. */
  public record Recorded(PendingDeletion deletion, boolean added) {}

  /** Records the deletion asked for; one already recorded keeps its first record. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  @PublishEvent(
      type = GuestDocumentDeletionRequested.class,
      when = "#result.added()",
      args = {"#asked.guestId()", "#asked.documentId()", "#asked.actorId()"})
  public Recorded record(PendingDeletion asked) {
    return records
        .pendingDeletionOf(asked.documentId())
        .map(recorded -> new Recorded(recorded, false))
        .orElseGet(
            () -> {
              records.addPendingDeletion(asked);
              return new Recorded(asked, true);
            });
  }

  /**
   * Finishes a deletion whose object already left the store: guest and document locked (in that
   * order), the evidence written, the row removed and the record forgotten.
   *
   * @return whether the document was still there
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  @PublishEvent(
      type = GuestDocumentDeleted.class,
      when = "#result",
      args = {"#deletion.guestId()", "#deletion.documentId()", "#deletion.actorId()"})
  public boolean complete(PendingDeletion deletion) {
    Optional<GuestDocument> document =
        guests.findByIdForUpdate(deletion.guestId()).isPresent()
            ? records.findDocumentForUpdate(deletion.guestId(), deletion.documentId())
            : Optional.empty();
    document.ifPresent(
        row -> {
          records.addAction(
              Kind.DELETE, row, null, deletion.why(), deletion.actorId(), deletion.requestedOn());
          records.removeDocument(row);
        });
    records.removePendingDeletion(deletion.documentId());
    return document.isPresent();
  }
}
