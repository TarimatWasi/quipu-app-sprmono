package com.tarimatwasi.quipu.guest.adapter.out.persistence;

import com.tarimatwasi.quipu.guest.domain.GuestDocument;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.Kind;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.PendingDeletion;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.Why;
import com.tarimatwasi.quipu.guest.port.out.GuestDocumentActionExistsException;
import com.tarimatwasi.quipu.guest.port.out.GuestDocumentActionRepositoryPort;
import com.tarimatwasi.quipu.shared.adapter.out.persistence.AuditorJpaEntity;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.CursorToken;
import com.tarimatwasi.quipu.shared.paging.InvalidCursorException;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
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
  private final GuestDocumentDeletionJpaRepository deletions;
  private final EntityManager entityManager;

  GuestDocumentActionRepositoryAdapter(
      GuestDocumentJpaRepository documents,
      GuestDocumentActionJpaRepository actions,
      GuestDocumentPurgeJpaRepository purges,
      GuestDocumentDeletionJpaRepository deletions,
      EntityManager entityManager) {
    this.documents = documents;
    this.actions = actions;
    this.purges = purges;
    this.deletions = deletions;
    this.entityManager = entityManager;
  }

  @Override
  public Optional<GuestDocument> findDocumentForUpdate(Long guestId, Long documentId) {
    return documents
        .findWithLockByIdAndGuestId(documentId, guestId)
        .map(GuestDocumentJpaEntity::toDomain);
  }

  /**
   * Detached once read: the row is read-only, so a later lock on it in the same transaction must
   * load it again instead of finding it managed.
   */
  @Override
  public Optional<GuestDocument> findDocument(Long guestId, Long documentId) {
    return documents
        .findByIdAndGuestId(documentId, guestId)
        .map(
            row -> {
              entityManager.detach(row);
              return row.toDomain();
            });
  }

  /** Flushes, so that a second action on the document surfaces here as what it is. */
  @Override
  public GuestDocumentAction addAction(
      Kind kind,
      GuestDocument document,
      @Nullable Long replacementDocumentId,
      Why why,
      Long actorId,
      Instant performedAt) {
    try {
      return actions
          .saveAndFlush(
              new GuestDocumentActionJpaEntity(
                  kind, document, replacementDocumentId, why, actor(actorId), performedAt))
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
    purges.deleteByKey(storageKey);
  }

  @Override
  public boolean isReferenced(String storageKey) {
    return documents.existsByStorageKey(storageKey);
  }

  @Override
  public Optional<PendingDeletion> pendingDeletionOf(Long documentId) {
    return deletions.findByDocumentId(documentId).map(GuestDocumentDeletionJpaEntity::toDomain);
  }

  @Override
  public void addPendingDeletion(PendingDeletion deletion) {
    deletions.saveAndFlush(new GuestDocumentDeletionJpaEntity(deletion, actor(deletion.actorId())));
  }

  @Override
  public void removePendingDeletion(Long documentId) {
    deletions.deleteByDocument(documentId);
  }

  @Override
  public List<PendingDeletion> pendingDeletions(Instant askedBefore, int limit) {
    return deletions.findAskedBefore(askedBefore, Limit.of(limit)).stream()
        .map(GuestDocumentDeletionJpaEntity::toDomain)
        .toList();
  }

  /** The user by reference: no query, the column only needs the id. */
  private AuditorJpaEntity actor(Long actorId) {
    return entityManager.getReference(AuditorJpaEntity.class, actorId);
  }

  @Override
  public List<String> pendingPurges(Instant addedBefore, int limit) {
    return purges.findAddedBefore(addedBefore, Limit.of(limit)).stream()
        .map(GuestDocumentPurgeJpaEntity::storageKey)
        .toList();
  }

  /** Only the rule of one action per document is translated; any other failure stays as it is. */
  static RuntimeException conflict(DataIntegrityViolationException e) {
    return ViolatedConstraint.is(e, ONE_ACTION_CONSTRAINT)
        ? new GuestDocumentActionExistsException(e)
        : e;
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
