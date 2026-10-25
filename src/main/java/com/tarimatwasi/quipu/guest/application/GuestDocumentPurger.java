package com.tarimatwasi.quipu.guest.application;

import com.tarimatwasi.quipu.guest.domain.GuestDocument;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.Kind;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.PendingDeletion;
import com.tarimatwasi.quipu.guest.port.out.GuestDocumentActionExistsException;
import com.tarimatwasi.quipu.guest.port.out.GuestDocumentActionRepositoryPort;
import com.tarimatwasi.quipu.guest.port.out.GuestDocumentFilePort;
import com.tarimatwasi.quipu.guest.port.out.GuestDocumentStorageException;
import com.tarimatwasi.quipu.guest.port.out.GuestRepositoryPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Finishes what an action on a guest document left halfway (RN-37): the objects of the store that
 * no row references any more (the old file of a replacement, or the new one of a replacement that
 * did not commit) and the deletions whose row and evidence did not commit. Both are recorded in the
 * database first, so nothing is lost across a failure or a restart. Each step runs in a transaction
 * of its own and the store is only ever called between them, never with a row locked. Removing an
 * object twice is harmless, so two requests may race for one. Neither a key nor a personal datum is
 * ever logged: only counts.
 *
 * <p>Limit: there is no timer, because a scheduled task needs an ADR (BE-SPR-CON-01). A round runs
 * when the application starts and after every deletion or replacement; in between, what is pending
 * waits, and the deletions in course are hidden from the list of documents meanwhile. A job outside
 * the application can read {@code guest_document_purges} and {@code guest_document_deletions} if
 * that becomes too slow.
 */
@Component
public class GuestDocumentPurger {

  private static final Logger LOGGER = LoggerFactory.getLogger(GuestDocumentPurger.class);

  /** A round after an action: small, so that it does not hold the answer for long. */
  public static final int AFTER_AN_ACTION = 5;

  /** A round when the application starts. */
  static final int AT_STARTUP = 100;

  /**
   * A deletion or a pending object younger than this may still belong to a request in course (its
   * R2 call and its commit end well within it), so a round leaves it alone.
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

  /**
   * Records the deletion asked for, committed before its object leaves the store. A deletion
   * already recorded (a repeated request) keeps its first record: who asked first, and when.
   *
   * @return the deletion as recorded
   */
  public PendingDeletion begin(PendingDeletion asked) {
    try {
      return Objects.requireNonNull(
          separate.execute(
              status ->
                  records
                      .pendingDeletionOf(asked.documentId())
                      .orElseGet(
                          () -> {
                            records.addPendingDeletion(asked);
                            return asked;
                          })));
    } catch (DataIntegrityViolationException raced) {
      // Another request recorded it at the same time: its record is the one kept.
      return Objects.requireNonNull(
              separate.execute(status -> records.pendingDeletionOf(asked.documentId())))
          .orElseThrow(() -> raced);
    }
  }

  /**
   * Finishes a deletion whose object already left the store, in a transaction of its own: guest and
   * document locked (in that order), the evidence written with the administrator who asked and
   * when, the row removed and the record forgotten.
   *
   * @return whether the document was still there; if not, or if its evidence was already written,
   *     the record is only forgotten
   */
  public boolean complete(PendingDeletion deletion) {
    try {
      return completeLocked(deletion);
    } catch (GuestDocumentActionExistsException alreadyDone) {
      // Another action wrote the evidence of the document: this deletion has nothing left to do.
      separate.executeWithoutResult(s -> records.removePendingDeletion(deletion.documentId()));
      return false;
    }
  }

  private boolean completeLocked(PendingDeletion deletion) {
    return Boolean.TRUE.equals(
        separate.execute(
            status -> {
              Optional<GuestDocument> document =
                  guests.findByIdForUpdate(deletion.guestId()).isPresent()
                      ? records.findDocumentForUpdate(deletion.guestId(), deletion.documentId())
                      : Optional.empty();
              document.ifPresent(
                  row -> {
                    records.addAction(
                        Kind.DELETE,
                        row,
                        null,
                        deletion.why(),
                        deletion.actorId(),
                        deletion.requestedOn());
                    records.removeDocument(row);
                  });
              records.removePendingDeletion(deletion.documentId());
              return document.isPresent();
            }));
  }

  /**
   * Removes at once the old object of a replacement that has just committed: no other request can
   * still be committing a row that uses it. One the store keeps stays pending for a round.
   */
  public void purgeObjectNow(String storageKey) {
    try {
      attempt(() -> purgeObject(storageKey));
    } catch (GuestDocumentStorageException storeFailed) {
      LOGGER.warn("The old object of a guest document replacement stays pending; retried later");
    }
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
    } catch (DataAccessException | TransactionException e) {
      LOGGER.warn("The pending guest document actions could not be read; retried later");
      return;
    }
    if (left > 0) {
      LOGGER.warn("{} guest document actions are still pending; retried later", left);
    }
  }

  /**
   * Removes the pending objects and finishes the pending deletions, oldest first. A store that
   * fails ends the round: the rest waits for the next one instead of holding the caller.
   *
   * @return how many of those read are still pending
   */
  int purgePending(int limit) {
    // What is younger than a request in course is left to it: a replacement whose commit is not
    // known yet may still make its new object referenced.
    Instant margin = clock.instant().minus(IN_COURSE);
    List<String> keys =
        Objects.requireNonNull(separate.execute(s -> records.pendingPurges(margin, limit)));
    List<PendingDeletion> deletions =
        Objects.requireNonNull(separate.execute(s -> records.pendingDeletions(margin, limit)));
    int done = 0;
    try {
      for (String key : keys) {
        done += attempt(() -> purgeObject(key));
      }
      for (PendingDeletion deletion : deletions) {
        done += attempt(() -> finish(deletion));
      }
    } catch (GuestDocumentStorageException storeFailed) {
      // The store is failing: what is left waits for the next round.
    }
    return keys.size() + deletions.size() - done;
  }

  /** One unit; one whose database fails stays pending and the round goes on. */
  private static int attempt(BooleanSupplier unit) {
    try {
      return unit.getAsBoolean() ? 1 : 0;
    } catch (DataAccessException | TransactionException e) {
      return 0;
    }
  }

  /**
   * A key that a row still uses is only forgotten, never removed: a replacement whose commit was
   * not known may have committed after all.
   */
  private boolean purgeObject(String key) {
    if (!Boolean.TRUE.equals(separate.execute(s -> records.isReferenced(key)))) {
      files.delete(key);
    }
    separate.executeWithoutResult(s -> records.removePurge(key));
    return true;
  }

  /**
   * The deletion as its request would have finished it: the object first (one already missing
   * counts as removed), then the row and the evidence.
   */
  private boolean finish(PendingDeletion deletion) {
    Objects.requireNonNull(
            separate.execute(s -> records.findDocument(deletion.guestId(), deletion.documentId())))
        .ifPresent(document -> files.delete(document.storageKey()));
    complete(deletion);
    return true;
  }
}
