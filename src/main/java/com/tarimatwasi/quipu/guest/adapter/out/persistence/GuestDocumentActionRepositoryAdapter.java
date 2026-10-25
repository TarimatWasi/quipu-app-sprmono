package com.tarimatwasi.quipu.guest.adapter.out.persistence;

import com.tarimatwasi.quipu.guest.domain.GuestDocument;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.Kind;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.Why;
import com.tarimatwasi.quipu.guest.port.out.GuestDocumentActionExistsException;
import com.tarimatwasi.quipu.guest.port.out.GuestDocumentActionRepositoryPort;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.CursorToken;
import com.tarimatwasi.quipu.shared.paging.InvalidCursorException;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

@Component
public class GuestDocumentActionRepositoryAdapter implements GuestDocumentActionRepositoryPort {

  private static final String ONE_ACTION_CONSTRAINT = "uq_guest_document_actions_document";

  private final GuestDocumentJpaRepository documents;
  private final GuestDocumentActionJpaRepository actions;
  private final GuestDocumentPurgeJpaRepository purges;

  GuestDocumentActionRepositoryAdapter(
      GuestDocumentJpaRepository documents,
      GuestDocumentActionJpaRepository actions,
      GuestDocumentPurgeJpaRepository purges) {
    this.documents = documents;
    this.actions = actions;
    this.purges = purges;
  }

  @Override
  public Optional<GuestDocument> findDocumentForUpdate(Long guestId, Long documentId) {
    return documents
        .findWithLockByIdAndGuestId(documentId, guestId)
        .map(GuestDocumentJpaEntity::toDomain);
  }

  /** Flushes, so that a second action on the document surfaces here as what it is. */
  @Override
  public GuestDocumentAction addAction(
      Kind kind, GuestDocument document, @Nullable Long replacementDocumentId, Why why) {
    try {
      return actions
          .saveAndFlush(
              new GuestDocumentActionJpaEntity(kind, document, replacementDocumentId, why))
          .toDomain();
    } catch (DataIntegrityViolationException e) {
      throw conflict(e);
    }
  }

  @Override
  public void removeDocument(GuestDocument document) {
    documents.deleteByIdAndGuest(document.id(), document.guestId());
  }

  @Override
  public CursorPage<GuestDocumentAction> actionsOf(Long guestId, PageQuery page) {
    Limit limit = Limit.of(page.limit() + 1);
    List<GuestDocumentActionJpaEntity> rows;
    if (page.after() == null) {
      rows = actions.findByGuestIdOrderByCreatedDateDescIdDesc(guestId, limit);
    } else {
      List<String> key = CursorToken.decode(page.after(), 2);
      rows = actions.findPageAfter(guestId, date(key.get(0)), id(key.get(1)), limit);
    }
    // One more than the limit: the extra row only says that there is a next page.
    return CursorPage.of(
        rows.stream().map(GuestDocumentActionJpaEntity::toDomain).toList(),
        page.limit(),
        action -> CursorToken.encode(action.performedAt().toString(), action.id().toString()));
  }

  @Override
  public void addPurge(String storageKey) {
    purges.saveAndFlush(new GuestDocumentPurgeJpaEntity(storageKey));
  }

  @Override
  public void removePurge(String storageKey) {
    purges.findByStorageKey(storageKey).ifPresent(purges::delete);
  }

  @Override
  public List<String> pendingPurges(int limit) {
    return purges.findAllByOrderByIdAsc(Limit.of(limit)).stream()
        .map(GuestDocumentPurgeJpaEntity::storageKey)
        .toList();
  }

  /** Only the rule of one action per document is translated; any other failure stays as it is. */
  static RuntimeException conflict(DataIntegrityViolationException e) {
    for (Throwable cause = e; cause != null; cause = cause.getCause()) {
      if (cause instanceof ConstraintViolationException violation
          && ONE_ACTION_CONSTRAINT.equals(violation.getConstraintName())) {
        return new GuestDocumentActionExistsException(e);
      }
    }
    return e;
  }

  private static Instant date(String text) {
    try {
      return Instant.parse(text);
    } catch (DateTimeParseException notADate) {
      throw new InvalidCursorException();
    }
  }

  private static Long id(String text) {
    try {
      return Long.valueOf(text);
    } catch (NumberFormatException notAnId) {
      throw new InvalidCursorException();
    }
  }
}
