package com.tarimatwasi.quipu.guest.application.service;

import com.tarimatwasi.quipu.guest.application.port.in.GuestDocumentUnavailableException;
import com.tarimatwasi.quipu.guest.application.port.in.InvalidOnboardingInputException;
import com.tarimatwasi.quipu.guest.application.port.in.OnboardingConflictException;
import com.tarimatwasi.quipu.guest.application.port.in.OnboardingConflictException.Reason;
import com.tarimatwasi.quipu.guest.application.port.in.OnboardingUseCase.DocumentUpload;
import com.tarimatwasi.quipu.guest.application.port.in.OnboardingUseCase.DocumentView;
import com.tarimatwasi.quipu.guest.application.port.out.GuestDocumentFilePort;
import com.tarimatwasi.quipu.guest.application.port.out.GuestDocumentStorageException;
import com.tarimatwasi.quipu.guest.application.port.out.OnboardingRepositoryPort;
import com.tarimatwasi.quipu.guest.domain.GuestDocument;
import com.tarimatwasi.quipu.shared.file.FileKind;
import com.tarimatwasi.quipu.shared.file.FileNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Stores a document a guest uploads, during the onboarding or later from «Mis datos» (RN-37,
 * TAR-242): the same checks of the file (RF-15) and of the limit of 5 per guest, and the same
 * cleanup of the bucket when the row is not committed. The caller holds the lock of the guest, so
 * two uploads of one guest cannot both pass the count.
 */
@Component
class GuestDocumentUploader {

  private static final Logger LOGGER = LoggerFactory.getLogger(GuestDocumentUploader.class);

  /** The name of a document whose browser name is unusable. */
  private static final String DEFAULT_NAME = "documento";

  private final OnboardingRepositoryPort records;
  private final GuestDocumentFilePort files;

  GuestDocumentUploader(OnboardingRepositoryPort records, GuestDocumentFilePort files) {
    this.records = records;
    this.files = files;
  }

  /**
   * The kind of the file, checked by its content before any row is locked.
   *
   * @throws InvalidOnboardingInputException if it is not a JPG, PNG or PDF of up to 5 MB
   */
  static FileKind kindOf(DocumentUpload upload) {
    byte[] content = upload.content();
    FileKind kind =
        (content.length > GuestDocument.MAX_BYTES ? null : FileKind.of(content).orElse(null));
    if (content.length == 0 || kind == null) {
      throw new InvalidOnboardingInputException(
          InvalidOnboardingInputException.Reason.DOCUMENT_INVALID_FILE);
    }
    return kind;
  }

  /**
   * Stores the file and its row for the guest, whose row the caller has locked.
   *
   * @throws OnboardingConflictException with DOCUMENT_LIMIT_REACHED if the guest has 5 already
   * @throws GuestDocumentUnavailableException if the file store failed; nothing was saved
   */
  DocumentView store(Long guestId, DocumentUpload upload, FileKind kind) {
    if (records.countDocuments(guestId) >= GuestDocument.MAX_PER_GUEST) {
      throw new OnboardingConflictException(Reason.DOCUMENT_LIMIT_REACHED);
    }
    byte[] content = upload.content();
    String name = FileNames.sanitized(upload.originalName(), DEFAULT_NAME);
    String key = put(content, kind);
    boolean removedIfNotCommitted = removeUnlessCommitted(guestId, key);
    GuestDocument saved = null;
    try {
      saved = records.addDocument(guestId, key, name, kind.contentType(), content.length);
    } finally {
      if (saved == null && !removedIfNotCommitted) {
        discard(guestId, key);
      }
    }
    return view(saved);
  }

  /** A document as the guest sees it: never its file nor a link to it. */
  static DocumentView view(GuestDocument document) {
    return new DocumentView(
        document.id(),
        document.originalName(),
        document.contentType(),
        document.sizeBytes(),
        document.uploadedAt());
  }

  private String put(byte[] content, FileKind kind) {
    try {
      return files.store(content, kind);
    } catch (GuestDocumentStorageException e) {
      throw new GuestDocumentUnavailableException(e);
    }
  }

  /**
   * Inside a transaction the object is removed whenever the transaction does not commit: the insert
   * failed, or something after it did. Returns true if it will be; otherwise the caller removes it.
   */
  private boolean removeUnlessCommitted(Long guestId, String key) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      return false;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (status != STATUS_COMMITTED) {
              discard(guestId, key);
            }
          }
        });
    return true;
  }

  /**
   * A failed cleanup is logged with the internal id of the guest only: never the storage key, the
   * name nor the content (TAR-205).
   */
  private void discard(Long guestId, String key) {
    try {
      files.delete(key);
    } catch (GuestDocumentStorageException cleanupFailed) {
      // TAR-205: the class names only; the message and trace of the SDK name the R2 host
      // (account and bucket) and may name the key.
      Throwable cause = cleanupFailed.getCause();
      LOGGER.error(
          "A document of guest {} stays in the bucket without a row ({}, cause {})",
          guestId,
          cleanupFailed.getClass().getName(),
          cause == null ? "-" : cause.getClass().getName());
    }
  }
}
