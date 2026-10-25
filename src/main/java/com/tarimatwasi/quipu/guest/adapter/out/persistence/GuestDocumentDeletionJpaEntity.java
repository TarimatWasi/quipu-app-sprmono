package com.tarimatwasi.quipu.guest.adapter.out.persistence;

import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.Channel;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.PendingDeletion;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.Reason;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.Why;
import com.tarimatwasi.quipu.shared.adapter.out.persistence.AuditorJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.hibernate.annotations.Immutable;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.domain.AbstractAuditable;

/**
 * A deletion of a guest document asked for and not finished yet (RN-37). The actor and the time are
 * those of the request, set here; the row is removed when the evidence is written.
 */
@Entity
@Immutable
@Table(name = "guest_document_deletions")
public class GuestDocumentDeletionJpaEntity extends AbstractAuditable<AuditorJpaEntity, Long> {

  @Column(name = "guest_id")
  private Long guestId;

  @Column(name = "document_id")
  private Long documentId;

  @Enumerated(EnumType.STRING)
  @Column(name = "reason")
  private Reason reason;

  @Column(name = "requested_at")
  private @Nullable LocalDate requestedAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "request_channel")
  private @Nullable Channel requestChannel;

  @Column(name = "note")
  private @Nullable String note;

  @Column(name = "content_type")
  private String contentType;

  @Column(name = "size_bytes")
  private long sizeBytes;

  protected GuestDocumentDeletionJpaEntity() {}

  GuestDocumentDeletionJpaEntity(PendingDeletion deletion, AuditorJpaEntity actor) {
    this.guestId = deletion.guestId();
    this.documentId = deletion.documentId();
    this.reason = deletion.why().reason();
    this.requestedAt = deletion.why().requestedAt();
    this.requestChannel = deletion.why().requestChannel();
    this.note = deletion.why().note();
    this.contentType = deletion.contentType();
    this.sizeBytes = deletion.sizeBytes();
    LocalDateTime at = LocalDateTime.ofInstant(deletion.requestedOn(), ZoneId.systemDefault());
    setCreatedBy(actor);
    setLastModifiedBy(actor);
    setCreatedDate(at);
    setLastModifiedDate(at);
  }

  PendingDeletion toDomain() {
    return new PendingDeletion(
        guestId,
        documentId,
        new Why(reason, requestedAt, requestChannel, note),
        contentType,
        sizeBytes,
        getCreatedBy().orElseThrow().getId(),
        getCreatedDate().orElseThrow().atZone(ZoneId.systemDefault()).toInstant());
  }

  @Override
  public String toString() {
    // Never the note: it is not logged.
    return "GuestDocumentDeletionJpaEntity[id=" + getId() + ", documentId=" + documentId + "]";
  }
}
