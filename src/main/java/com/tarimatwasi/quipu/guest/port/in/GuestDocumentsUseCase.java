package com.tarimatwasi.quipu.guest.port.in;

import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.Channel;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.Reason;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.Why;
import com.tarimatwasi.quipu.guest.port.in.OnboardingUseCase.DocumentUpload;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.InvalidCursorException;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * CU-27, RN-37: the administrator reads the documents a guest uploaded in its onboarding and, only
 * on an ARCO request of the holder or an upload error, deletes or replaces one, with evidence.
 */
public interface GuestDocumentsUseCase {

  /** A document with a link that downloads it for 5 minutes. */
  record GuestDocumentView(
      Long id,
      String originalName,
      String contentType,
      long sizeBytes,
      Instant uploadedAt,
      String url,
      Instant urlExpiresAt) {}

  /**
   * Why a document is deleted or replaced, as the request carried it. An empty or whitespace-only
   * field counts as absent; the rest is stripped.
   */
  record DocumentActionRequest(
      @Nullable String reason,
      @Nullable String requestedAt,
      @Nullable String requestChannel,
      @Nullable String note) {

    public DocumentActionRequest {
      reason = present(reason);
      requestedAt = present(requestedAt);
      requestChannel = present(requestChannel);
      note = present(note);
    }

    /**
     * Checks the rules of RN-37 on the day of the business.
     *
     * @throws InvalidDocumentActionException naming the first field that breaks them
     */
    public void check(LocalDate today) {
      why(today);
    }

    /**
     * The checked reason: with ARCO_REQUEST the date (on or before today) and the channel are
     * required, with UPLOAD_ERROR neither may be sent, and the note has at most 500 characters.
     *
     * @throws InvalidDocumentActionException naming the first field that breaks them
     */
    public Why why(LocalDate today) {
      Reason kind = parse(Reason.class, reason, "reason");
      LocalDate date = date(today);
      Channel channel =
          requestChannel == null ? null : parse(Channel.class, requestChannel, "requestChannel");
      if (kind == Reason.ARCO_REQUEST && date == null) {
        throw new InvalidDocumentActionException("requestedAt");
      }
      if (kind == Reason.ARCO_REQUEST && channel == null) {
        throw new InvalidDocumentActionException("requestChannel");
      }
      if (kind == Reason.UPLOAD_ERROR && date != null) {
        throw new InvalidDocumentActionException("requestedAt");
      }
      if (kind == Reason.UPLOAD_ERROR && channel != null) {
        throw new InvalidDocumentActionException("requestChannel");
      }
      if (note != null && note.length() > GuestDocumentAction.MAX_NOTE) {
        throw new InvalidDocumentActionException("note");
      }
      return new Why(kind, date, channel, note);
    }

    private @Nullable LocalDate date(LocalDate today) {
      if (requestedAt == null) {
        return null;
      }
      LocalDate date;
      try {
        date = LocalDate.parse(requestedAt);
      } catch (DateTimeParseException notADate) {
        throw new InvalidDocumentActionException("requestedAt");
      }
      if (date.isAfter(today)) {
        throw new InvalidDocumentActionException("requestedAt");
      }
      return date;
    }

    private static <E extends Enum<E>> E parse(
        Class<E> type, @Nullable String value, String field) {
      if (value == null) {
        throw new InvalidDocumentActionException(field);
      }
      try {
        return Enum.valueOf(type, value);
      } catch (IllegalArgumentException unknown) {
        throw new InvalidDocumentActionException(field);
      }
    }

    private static @Nullable String present(@Nullable String value) {
      return value == null || value.isBlank() ? null : value.strip();
    }
  }

  /** Evidence of one deletion or replacement; never the name, the key nor the content. */
  record DocumentActionView(
      Long id,
      String action,
      Long documentId,
      @Nullable Long replacementDocumentId,
      String reason,
      @Nullable LocalDate requestedAt,
      @Nullable String requestChannel,
      @Nullable String note,
      String contentType,
      long sizeBytes,
      Long actorId,
      Instant performedAt) {}

  /**
   * The documents of the guest, oldest first, each with a freshly signed link.
   *
   * @throws GuestNotFoundException if there is no guest with that id
   * @throws GuestDocumentUnavailableException if the file store could not sign the links
   */
  List<GuestDocumentView> documents(Long guestId);

  /**
   * Deletes the document: first its object from the store (one already missing counts as deleted),
   * then, in one transaction, its row, writing the evidence of the deletion.
   *
   * @throws InvalidDocumentActionException if the reason breaks the rules of RN-37
   * @throws GuestDocumentNotFoundException if the document is not one of that guest
   * @throws GuestDocumentUnavailableException if the store failed; nothing changed
   */
  void delete(Long guestId, Long documentId, DocumentActionRequest request);

  /**
   * Replaces the document with a new one (with a new id): the new object is stored, then the row is
   * swapped and the evidence written in one transaction, and the old object is removed after the
   * commit, retried until it is.
   *
   * @throws InvalidDocumentActionException if the reason breaks the rules of RN-37
   * @throws InvalidOnboardingInputException if the file is not a JPG, PNG or PDF of up to 5 MB
   * @throws GuestDocumentNotFoundException if the document is not one of that guest
   * @throws GuestDocumentUnavailableException if the store failed; nothing changed
   */
  GuestDocumentView replace(
      Long guestId, Long documentId, DocumentActionRequest request, DocumentUpload file);

  /**
   * One page of the deletions and replacements of the documents of the guest, newest first.
   *
   * @throws GuestNotFoundException if there is no guest with that id
   * @throws InvalidCursorException if the cursor is not one this service issued
   */
  CursorPage<DocumentActionView> actions(Long guestId, PageQuery page);
}
