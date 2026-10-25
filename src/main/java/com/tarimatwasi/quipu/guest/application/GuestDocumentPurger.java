package com.tarimatwasi.quipu.guest.application;

import com.tarimatwasi.quipu.guest.port.out.GuestDocumentActionRepositoryPort;
import com.tarimatwasi.quipu.guest.port.out.GuestDocumentFilePort;
import com.tarimatwasi.quipu.guest.port.out.GuestDocumentStorageException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Removes from the store the objects that no row references any more (RN-37): the old file of a
 * replaced document, or a new one whose transaction did not commit. Each one is recorded in the
 * database before it is removed, so a failed removal stays pending, also across a restart, and is
 * retried after every committed action on a document. Removing an object twice is harmless, so two
 * requests may race for it. Each method runs in a transaction of its own, so it may be called after
 * the caller's has ended.
 *
 * <p>Limit: retried on the next action, not on a timer, because the architecture rules keep
 * {@code @EnableScheduling} out (BE-SPR-CON-01); a pending object waits for the next deletion or
 * replacement. A job outside the application can read {@code guest_document_purges} if that becomes
 * too slow.
 */
@Component
public class GuestDocumentPurger {

  /** The most objects one round removes. */
  private static final int BATCH = 100;

  private final GuestDocumentActionRepositoryPort records;
  private final GuestDocumentFilePort files;

  GuestDocumentPurger(GuestDocumentActionRepositoryPort records, GuestDocumentFilePort files) {
    this.records = records;
    this.files = files;
  }

  /** Records the object as pending, on its own: also when the caller's transaction rolled back. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void schedule(String storageKey) {
    records.addPurge(storageKey);
  }

  /**
   * Removes the pending objects, oldest first, and forgets each one the store removed; the others
   * stay pending.
   *
   * @return how many are still pending of those tried
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public int purgePending() {
    int failed = 0;
    for (String key : records.pendingPurges(BATCH)) {
      try {
        files.delete(key);
      } catch (GuestDocumentStorageException storeFailed) {
        failed++;
        continue;
      }
      records.removePurge(key);
    }
    return failed;
  }
}
