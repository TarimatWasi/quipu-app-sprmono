package com.tarimatwasi.quipu.guest.port.out;

import com.tarimatwasi.quipu.guest.domain.GuestDocument;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.Kind;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.Why;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.InvalidCursorException;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The deletions and replacements of the guest documents (RN-37): the evidence, which is only ever
 * inserted, and the objects of the store that are left to remove.
 */
public interface GuestDocumentActionRepositoryPort {

  /**
   * The document of that guest, locked until the transaction ends; empty if it is not one of that
   * guest. Needs a transaction.
   */
  Optional<GuestDocument> findDocumentForUpdate(Long guestId, Long documentId);

  /**
   * Inserts the evidence of the action on the document, by the user of the session.
   *
   * @throws GuestDocumentActionExistsException if the document already has its action
   */
  GuestDocumentAction addAction(
      Kind kind, GuestDocument document, @Nullable Long replacementDocumentId, Why why);

  /** Removes the row of the document; its evidence must have been inserted first. */
  void removeDocument(GuestDocument document);

  /**
   * One page of the actions of the guest, newest first.
   *
   * @throws InvalidCursorException if the cursor of the page is not one this service issued
   */
  CursorPage<GuestDocumentAction> actionsOf(Long guestId, PageQuery page);

  /** Records that the object under the key is to be removed from the store. */
  void addPurge(String storageKey);

  /** Forgets the object under the key: it was removed from the store. */
  void removePurge(String storageKey);

  /** The keys of the objects still to remove, oldest first, at most {@code limit}. */
  List<String> pendingPurges(int limit);
}
