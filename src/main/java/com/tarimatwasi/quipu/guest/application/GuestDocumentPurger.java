package com.tarimatwasi.quipu.guest.application;

import com.tarimatwasi.quipu.guest.domain.GuestDocument;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.Kind;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.PendingDeletion;
import com.tarimatwasi.quipu.guest.port.out.GuestDocumentActionRepositoryPort;
import com.tarimatwasi.quipu.guest.port.out.GuestDocumentFilePort;
import com.tarimatwasi.quipu.guest.port.out.GuestDocumentStorageException;
import com.tarimatwasi.quipu.guest.port.out.GuestRepositoryPort;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Finishes what an action on a guest document left halfway (RN-37): the objects of the store that
 * no row references any more (the old file of a replacement, or the new one of a replacement that
 * did not commit) and the deletions whose object left the store but whose row and evidence did not
 * commit. Both are recorded in the database first, so nothing is lost across a failure or a
 * restart. Each unit runs in a transaction of its own, so it may run after the caller's has ended.
 * Removing an object twice is harmless, so two requests may race for one. Neither a key nor a
 * personal datum is ever logged: only counts.
 *
 * <p>Limit: there is no timer, because the architecture rules keep {@code @EnableScheduling} out
 * (BE-SPR-CON-01). A round runs when the application starts and after every committed deletion or
 * replacement; in between, what is pending waits. A job outside the application can read {@code
 * guest_document_purges} and {@code guest_document_deletions} if that becomes too slow.
 */
@Component
public class GuestDocumentPurger {

  private static final Logger LOGGER = LoggerFactory.getLogger(GuestDocumentPurger.class);

  /** A round after an action: small, so that it does not hold the answer for long. */
  public static final int AFTER_AN_ACTION = 20;

  /** A round when the application starts. */
  static final int AT_STARTUP = 100;

  /**
   * A deletion younger than this may still be running in its own request (its R2 call and its row
   * locks end well within it), so a round leaves it alone.
   */
  static final Duration IN_COURSE = Duration.ofMinutes(1);

  private final GuestRepositoryPort guests;
  private final GuestDocumentActionRepositoryPort records;
  private final GuestDocumentFilePort files;
  private final TransactionTemplate separate;
  private final Clock clock;

  GuestDocumentPurger(
      GuestRepositoryPort guests,
      GuestDocumentActionRepositoryPort records,
      GuestDocumentFilePort files,
      PlatformTransactionManager transactions,
      Clock clock) {
    this.guests = guests;
    this.records = records;
    this.files = files;
    this.separate = new TransactionTemplate(transactions);
    this.separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.clock = clock;
  }

  /** Records the object as pending, on its own: also when the caller's transaction rolled back. */
  public void schedule(String storageKey) {
    separate.executeWithoutResult(status -> records.addPurge(storageKey));
  }

  /** Records the deletion asked for, committed before its object leaves the store. */
  public void begin(PendingDeletion deletion) {
    separate.executeWithoutResult(status -> records.addPendingDeletion(deletion));
  }

  /** Forgets a deletion whose object never left the store. */
  public void cancel(Long documentId) {
    separate.executeWithoutResult(status -> records.removePendingDeletion(documentId));
  }

  /** The round when the application starts; a store or a database that fails does not stop it. */
  @EventListener(ApplicationReadyEvent.class)
  public void purgeAtStartup() {
    purge(AT_STARTUP);
  }

  /** One round of at most {@code limit} of each; what stays is counted in a warning. */
  public void purge(int limit) {
    int left;
    try {
      left = purgePending(limit);
    } catch (DataAccessException | TransactionException | GuestDocumentStorageException e) {
      LOGGER.warn("The pending guest document actions could not be read; retried later");
      return;
    }
    if (left > 0) {
      LOGGER.warn("{} guest document actions are still pending; retried later", left);
    }
  }

  /**
   * Removes the pending objects and finishes the pending deletions, oldest first.
   *
   * @return how many of those tried are still pending
   */
  int purgePending(int limit) {
    int left = 0;
    List<String> keys = Objects.requireNonNull(separate.execute(s -> records.pendingPurges(limit)));
    for (String key : keys) {
      if (!attempt(status -> purgeObject(key))) {
        left++;
      }
    }
    List<PendingDeletion> deletions =
        Objects.requireNonNull(
            separate.execute(
                s -> records.pendingDeletions(clock.instant().minus(IN_COURSE), limit)));
    for (PendingDeletion deletion : deletions) {
      if (!attempt(status -> finish(deletion))) {
        left++;
      }
    }
    return left;
  }

  /** One unit in its own transaction; one that fails stays pending and the round goes on. */
  private boolean attempt(TransactionCallback<Boolean> unit) {
    try {
      return Boolean.TRUE.equals(separate.execute(unit));
    } catch (DataAccessException | TransactionException e) {
      return false;
    }
  }

  /**
   * A key that a row still uses is only forgotten, never removed: a replacement whose commit was
   * not known may have committed after all.
   */
  private boolean purgeObject(String key) {
    if (!records.isReferenced(key)) {
      try {
        files.delete(key);
      } catch (GuestDocumentStorageException storeFailed) {
        return false;
      }
    }
    records.removePurge(key);
    return true;
  }

  /**
   * The deletion as its request would have finished it: guest and document locked, the object
   * removed (one already missing counts as removed), the evidence written with the administrator
   * who asked and when, and the row removed.
   */
  private boolean finish(PendingDeletion deletion) {
    if (guests.findByIdForUpdate(deletion.guestId()).isPresent()) {
      Optional<GuestDocument> document =
          records.findDocumentForUpdate(deletion.guestId(), deletion.documentId());
      if (document.isPresent()) {
        try {
          files.delete(document.get().storageKey());
        } catch (GuestDocumentStorageException storeFailed) {
          return false;
        }
        records.addAction(
            Kind.DELETE,
            document.get(),
            null,
            deletion.why(),
            deletion.actorId(),
            deletion.requestedOn());
        records.removeDocument(document.get());
      }
    }
    records.removePendingDeletion(deletion.documentId());
    return true;
  }
}
