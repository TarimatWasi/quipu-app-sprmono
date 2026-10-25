package com.tarimatwasi.quipu.guest.domain;

import java.time.Instant;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * Evidence of one deletion or replacement of a guest document by an administrator (RN-37): who,
 * when and why. It never carries the name, the storage key nor the content of the file; it is only
 * ever inserted.
 */
public record GuestDocumentAction(
    Long id,
    Long guestId,
    Kind kind,
    Long documentId,
    @Nullable Long replacementDocumentId,
    Why why,
    String contentType,
    long sizeBytes,
    Long actorId,
    Instant performedAt) {

  /** The longest note. */
  public static final int MAX_NOTE = 500;

  /** What was done to the document. */
  public enum Kind {
    DELETE,
    REPLACE
  }

  /** Why the document was deleted or replaced: only these two (RN-37). */
  public enum Reason {
    ARCO_REQUEST,
    UPLOAD_ERROR
  }

  /** The channel the holder made the ARCO request through. */
  public enum Channel {
    WHATSAPP,
    EMAIL,
    IN_PERSON,
    OTHER
  }

  /**
   * The reason of the action, already checked: with ARCO_REQUEST the date and the channel are
   * there, with UPLOAD_ERROR neither is; the note is a reference only, never blank.
   */
  public record Why(
      Reason reason,
      @Nullable LocalDate requestedAt,
      @Nullable Channel requestChannel,
      @Nullable String note) {}

  /**
   * A deletion asked for and not finished yet: recorded before the object leaves the store, so the
   * evidence of who asked and when survives a transaction that fails afterwards (RN-37).
   */
  public record PendingDeletion(
      Long guestId,
      Long documentId,
      Why why,
      String contentType,
      long sizeBytes,
      Long actorId,
      Instant requestedOn) {}
}
