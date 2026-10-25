package com.tarimatwasi.quipu.guest.port.out;

import com.tarimatwasi.quipu.guest.domain.GuestDocument;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.Kind;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.PendingDeletion;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.Why;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.InvalidCursorException;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The deletions and replacements of the guest documents (RN-37): the evidence, which is only ever
 * inserted, the deletions still in course, and the objects of the store that are left to remove.
 * Every write needs a transaction.
 */
public interface GuestDocumentActionRepositoryPort {

  /**
   * The document of that guest, locked until the transaction ends; empty if it is not one of that
   * guest.
   */
  Optional<GuestDocument> findDocumentForUpdate(Long guestId, Long documentId);

  /** The document of that guest, without a lock; empty if it is not one of that guest. */
  Optional<GuestDocument> findDocument(Long guestId, Long documentId);

  /**
   * Inserts the evidence of the action on the document, by that administrator at that time.
   *
   * @throws GuestDocumentActionExistsException if the document already has its action
   */
  GuestDocumentAction addAction(
      Kind kind,
      GuestDocument document,
      @Nullable Long replacementDocumentId,
      Why why,
      Long actorId,
      Instant performedAt);

  /** Removes the row of the document; its evidence must have been inserted first. */
  void removeDocument(GuestDocument document);

  /**
   * One page of the actions of the guest, newest first.
   *
   * @throws InvalidCursorException if the cursor of the page is not one this service issued
   */
  CursorPage<GuestDocumentAction> actionsOf(Long guestId, PageQuery page);

  /** The deletion of the document still in course, if any. */
  Optional<PendingDeletion> pendingDeletionOf(Long documentId);

  /** Records the deletion asked for, before its object leaves the store. */
  void addPendingDeletion(PendingDeletion deletion);

  /** Forgets the deletion of the document: done, or the document is gone. */
  void removePendingDeletion(Long documentId);

  /** The deletions asked for before that instant and still in course, oldest first. */
  List<PendingDeletion> pendingDeletions(Instant askedBefore, int limit);

  /** Records that the object under the key is to be removed from the store. */
  void addPurge(String storageKey);

  /** Forgets the object under the key: it was removed from the store, or a row still uses it. */
  void removePurge(String storageKey);

  /**
   * The keys of the objects still to remove that were recorded before that instant, oldest first,
   * at most {@code limit}.
   */
  List<String> pendingPurges(Instant addedBefore, int limit);

  /** Whether a document row stores its file under the key. */
  boolean isReferenced(String storageKey);
}
