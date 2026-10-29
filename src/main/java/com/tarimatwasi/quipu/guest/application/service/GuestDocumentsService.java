package com.tarimatwasi.quipu.guest.application.service;

import com.tarimatwasi.quipu.guest.application.port.in.GuestDocumentActionFailedException;
import com.tarimatwasi.quipu.guest.application.port.in.GuestDocumentNotFoundException;
import com.tarimatwasi.quipu.guest.application.port.in.GuestDocumentUnavailableException;
import com.tarimatwasi.quipu.guest.application.port.in.GuestDocumentsUseCase;
import com.tarimatwasi.quipu.guest.application.port.in.GuestNotFoundException;
import com.tarimatwasi.quipu.guest.application.port.in.InvalidOnboardingInputException;
import com.tarimatwasi.quipu.guest.application.port.in.OnboardingUseCase.DocumentUpload;
import com.tarimatwasi.quipu.guest.application.port.out.GuestDocumentActionExistsException;
import com.tarimatwasi.quipu.guest.application.port.out.GuestDocumentActionRepositoryPort;
import com.tarimatwasi.quipu.guest.application.port.out.GuestDocumentFilePort;
import com.tarimatwasi.quipu.guest.application.port.out.GuestDocumentStorageException;
import com.tarimatwasi.quipu.guest.application.port.out.GuestRepositoryPort;
import com.tarimatwasi.quipu.guest.application.port.out.OnboardingRepositoryPort;
import com.tarimatwasi.quipu.guest.domain.GuestDocument;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.Kind;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.PendingDeletion;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.Why;
import com.tarimatwasi.quipu.shared.file.FileKind;
import com.tarimatwasi.quipu.shared.file.FileNames;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * RN-37. A deletion or a replacement locks the row of the guest and then the one of the document
 * (the order of the onboarding), so of two actions on one document the second waits and then finds
 * it gone. Neither the storage key nor the signed URL nor the name of a file is ever logged.
 */
@Service
@PreAuthorize("hasRole('ADMIN')") // BE-SPR-SEC-02: admin use case, besides the URL rule
public class GuestDocumentsService implements GuestDocumentsUseCase {

  private static final Logger LOGGER = LoggerFactory.getLogger(GuestDocumentsService.class);

  /** The name of a document whose browser name is unusable. */
  private static final String DEFAULT_NAME = "documento";

  private final GuestRepositoryPort guests;
  private final OnboardingRepositoryPort records;
  private final GuestDocumentActionRepositoryPort actions;
  private final GuestDocumentFilePort files;
  private final GuestDocumentPurger purger;
  private final TransactionTemplate transaction;
  private final Clock clock;

  GuestDocumentsService(
      GuestRepositoryPort guests,
      OnboardingRepositoryPort records,
      GuestDocumentActionRepositoryPort actions,
      GuestDocumentFilePort files,
      GuestDocumentPurger purger,
      TransactionTemplate transaction,
      Clock clock) {
    this.guests = guests;
    this.records = records;
    this.actions = actions;
    this.files = files;
    this.purger = purger;
    this.transaction = transaction;
    this.clock = clock;
  }

  @Override
  @Transactional(readOnly = true)
  public List<GuestDocumentView> documents(Long guestId) {
    if (guests.findById(guestId).isEmpty()) {
      throw new GuestNotFoundException();
    }
    Instant expiresAt = clock.instant().plus(GuestDocument.URL_VALIDITY);
    try {
      // A document whose deletion is in course is not among them: no link for a gone object.
      return records.documentsOf(guestId).stream()
          .map(document -> view(document, expiresAt))
          .toList();
    } catch (GuestDocumentStorageException e) {
      throw new GuestDocumentUnavailableException(e);
    }
  }

  /**
   * The order of the contract: the object first, then the row and the evidence. Before the object
   * leaves the store the deletion is recorded on its own (who, when, why), and the store is called
   * with no row locked. A store that fails may still have removed the object, so the record stays:
   * the document is no longer listed, and the repeated action or the purger finish it with that
   * same evidence. Only a failure after the object left is the 500 of the contract; one before it
   * is answered as usual. TAR-234: a document already deleted, by another request at the same time
   * or before, is answered as deleted; its first evidence stays and nothing else is written.
   */
  @Override
  public void delete(Long actorId, Long guestId, Long documentId, DocumentActionRequest request) {
    Why why = request.why(LocalDate.now(clock));
    GuestDocument document;
    try {
      document =
          Objects.requireNonNull(transaction.execute(status -> current(guestId, documentId)));
    } catch (GuestDocumentNotFoundException e) {
      goneByADeletion(guestId, documentId);
      return;
    }
    Optional<PendingDeletion> pending =
        purger.begin(
            new PendingDeletion(
                guestId,
                document.id(),
                why,
                document.contentType(),
                document.sizeBytes(),
                actorId,
                clock.instant()));
    if (pending.isEmpty()) {
      // Another deletion recorded it at the same time and has already finished it.
      goneByADeletion(guestId, documentId);
      return;
    }
    try {
      files.delete(document.storageKey());
    } catch (GuestDocumentStorageException e) {
      throw new GuestDocumentUnavailableException(e);
    }
    boolean found;
    try {
      found = purger.complete(pending.get());
    } catch (DataAccessException | TransactionException e) {
      // Internal ids only: never the key, the cause or a personal datum.
      LOGGER.error(
          "The deletion of the guest document {} asked by user {} did not finish; it stays pending",
          documentId,
          actorId);
      throw new GuestDocumentActionFailedException(e);
    }
    if (!found) {
      // Another action got the document first.
      goneByADeletion(guestId, documentId);
    }
    purger.purge(GuestDocumentPurger.AFTER_AN_ACTION);
  }

  /**
   * TAR-234: a document that is gone is deleted already only if its evidence is a deletion; one
   * that a replacement got, or that never belonged to the guest, is not found.
   */
  private void goneByADeletion(Long guestId, Long documentId) {
    if (!Boolean.TRUE.equals(
        transaction.execute(status -> actions.wasDeleted(guestId, documentId)))) {
      throw new GuestDocumentNotFoundException();
    }
  }

  /**
   * The new file goes to the store with no transaction open; then the row is swapped and the
   * evidence written in one transaction, with the rows locked. A new object that no committed row
   * references is removed, or left pending; the old one is removed after the commit.
   */
  @Override
  public GuestDocumentView replace(
      Long actorId,
      Long guestId,
      Long documentId,
      DocumentActionRequest request,
      DocumentUpload upload) {
    Why why = request.why(LocalDate.now(clock));
    byte[] content = upload.content();
    FileKind kind =
        (content.length > GuestDocument.MAX_BYTES ? null : FileKind.of(content).orElse(null));
    if (content.length == 0 || kind == null) {
      throw new InvalidOnboardingInputException(
          InvalidOnboardingInputException.Reason.DOCUMENT_INVALID_FILE);
    }
    transaction.executeWithoutResult(status -> notBeingDeleted(current(guestId, documentId)));
    String key;
    try {
      key = files.store(content, kind);
    } catch (GuestDocumentStorageException e) {
      throw new GuestDocumentUnavailableException(e);
    }
    AtomicBoolean watched = new AtomicBoolean();
    Swap swap = null;
    try {
      swap =
          transaction.execute(
              status -> {
                // From here on the outcome of the transaction decides what happens to the object.
                afterRollback(key, documentId);
                watched.set(true);
                return swap(actorId, guestId, documentId, why, upload, kind, key);
              });
    } finally {
      if (swap == null && !watched.get()) {
        // The transaction never started: no row can reference the new object.
        discard(key, documentId);
      }
    }
    Swap done = Objects.requireNonNull(swap);
    purger.purgeObjectNow(done.oldKey());
    purger.purge(GuestDocumentPurger.AFTER_AN_ACTION);
    return done.view();
  }

  /** What a committed replacement leaves: the answer, and the old object to remove. */
  private record Swap(GuestDocumentView view, String oldKey) {}

  private Swap swap(
      Long actorId,
      Long guestId,
      Long documentId,
      Why why,
      DocumentUpload upload,
      FileKind kind,
      String key) {
    GuestDocument old = notBeingDeleted(locked(guestId, documentId));
    String url;
    try {
      url = files.signedUrl(key, kind);
    } catch (GuestDocumentStorageException e) {
      throw new GuestDocumentUnavailableException(e);
    }
    GuestDocument replacement =
        records.addDocument(
            guestId,
            key,
            FileNames.sanitized(upload.originalName(), DEFAULT_NAME),
            kind.contentType(),
            upload.content().length);
    record(Kind.REPLACE, old, replacement.id(), why, actorId, clock.instant());
    actions.removeDocument(old);
    // The old object is pending in the same transaction as the swap, so a crash cannot lose it.
    actions.addPurge(old.storageKey());
    return new Swap(
        new GuestDocumentView(
            replacement.id(),
            replacement.originalName(),
            replacement.contentType(),
            replacement.sizeBytes(),
            replacement.uploadedAt(),
            url,
            clock.instant().plus(GuestDocument.URL_VALIDITY)),
        old.storageKey());
  }

  @Override
  @Transactional(readOnly = true)
  public CursorPage<DocumentActionView> actions(Long guestId, PageQuery page) {
    if (guests.findById(guestId).isEmpty()) {
      throw new GuestNotFoundException();
    }
    return actions.actionsOf(guestId, page).map(GuestDocumentsService::view);
  }

  /** The guest and then its document, unlocked; one answer when either is missing. */
  private GuestDocument current(Long guestId, Long documentId) {
    if (guests.findById(guestId).isEmpty()) {
      throw new GuestDocumentNotFoundException();
    }
    return actions
        .findDocument(guestId, documentId)
        .orElseThrow(GuestDocumentNotFoundException::new);
  }

  /** A document whose deletion is in course is, for a replacement, a document that is gone. */
  private GuestDocument notBeingDeleted(GuestDocument document) {
    if (actions.pendingDeletionOf(document.id()).isPresent()) {
      throw new GuestDocumentNotFoundException();
    }
    return document;
  }

  /** The guest and then its document, both locked; one answer when either is missing. */
  private GuestDocument locked(Long guestId, Long documentId) {
    if (guests.findByIdForUpdate(guestId).isEmpty()) {
      throw new GuestDocumentNotFoundException();
    }
    return actions
        .findDocumentForUpdate(guestId, documentId)
        .orElseThrow(GuestDocumentNotFoundException::new);
  }

  /** Another action that got the document first is, for this one, a document that is gone. */
  private void record(
      Kind kind,
      GuestDocument document,
      @Nullable Long replacement,
      Why why,
      Long actorId,
      Instant performedAt) {
    try {
      actions.addAction(kind, document, replacement, why, actorId, performedAt);
    } catch (GuestDocumentActionExistsException e) {
      throw new GuestDocumentNotFoundException();
    }
  }

  private GuestDocumentView view(GuestDocument document, Instant expiresAt) {
    return new GuestDocumentView(
        document.id(),
        document.originalName(),
        document.contentType(),
        document.sizeBytes(),
        document.uploadedAt(),
        files.signedUrl(document.storageKey(), FileKind.ofContentType(document.contentType())),
        expiresAt);
  }

  private static DocumentActionView view(GuestDocumentAction action) {
    Why why = action.why();
    return new DocumentActionView(
        action.id(),
        action.kind().name(),
        action.documentId(),
        action.replacementDocumentId(),
        why.reason().name(),
        why.requestedAt(),
        why.requestChannel() == null ? null : why.requestChannel().name(),
        why.note(),
        action.contentType(),
        action.sizeBytes(),
        action.actorId(),
        action.performedAt());
  }

  /** The new object of a replacement that rolled back: removed, or else left pending. */
  private void discard(String key, Long documentId) {
    try {
      files.delete(key);
      return;
    } catch (GuestDocumentStorageException storeFailed) {
      // Recorded below, so that the next round removes it.
    }
    pend(key, documentId);
  }

  private void pend(String key, Long documentId) {
    try {
      purger.schedule(key);
    } catch (DataAccessException | TransactionException e) {
      LOGGER.error(
          "The new object of a failed replacement of the guest document {} stays in the bucket"
              + " without a row",
          documentId);
    }
  }

  /**
   * After a rollback the new object is removed. When the outcome is unknown the commit may have
   * happened, so the object is only left pending: the purger removes it unless a row uses it.
   */
  private void afterRollback(String key, Long documentId) {
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (status == STATUS_ROLLED_BACK) {
              discard(key, documentId);
            } else if (status == STATUS_UNKNOWN) {
              pend(key, documentId);
            }
          }
        });
  }
}
