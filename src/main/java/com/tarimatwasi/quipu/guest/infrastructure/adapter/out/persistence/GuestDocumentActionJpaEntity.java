package com.tarimatwasi.quipu.guest.infrastructure.adapter.out.persistence;

import com.tarimatwasi.quipu.guest.domain.GuestDocument;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.Channel;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.Kind;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.Reason;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.Why;
import com.tarimatwasi.quipu.shared.adapter.out.persistence.AuditorJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;
import org.hibernate.annotations.Immutable;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.domain.AbstractAuditable;

/**
 * Evidence of a deletion or replacement of a guest document (RN-37): inserted once, never updated
 * nor deleted. The actor and the time are set here, not by the auditing listener: a deletion that
 * is finished later keeps the administrator who asked for it and when, whoever is in session.
 */
@Entity
@Immutable
@Table(name = "guest_document_actions")
public class GuestDocumentActionJpaEntity extends AbstractAuditable<AuditorJpaEntity, Long> {

  @Column(name = "guest_id")
  private Long guestId;

  @Column(name = "document_id")
  private Long documentId;

  @Column(name = "replacement_document_id")
  private @Nullable Long replacementDocumentId;

  @Enumerated(EnumType.STRING)
  @Column(name = "action")
  private Kind action;

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

  protected GuestDocumentActionJpaEntity() {}

  GuestDocumentActionJpaEntity(
      Kind action,
      GuestDocument document,
      @Nullable Long replacementDocumentId,
      Why why,
      AuditorJpaEntity actor,
      Instant performedAt) {
    this.guestId = document.guestId();
    this.documentId = document.id();
    this.replacementDocumentId = replacementDocumentId;
    this.action = action;
    this.reason = why.reason();
    this.requestedAt = why.requestedAt();
    this.requestChannel = why.requestChannel();
    this.note = why.note();
    this.contentType = document.contentType();
    this.sizeBytes = document.sizeBytes();
    LocalDateTime at = LocalDateTime.ofInstant(performedAt, ZoneId.systemDefault());
    setCreatedBy(actor);
    setLastModifiedBy(actor);
    setCreatedDate(at);
    setLastModifiedDate(at);
  }

  GuestDocumentAction toDomain() {
    return new GuestDocumentAction(
        Objects.requireNonNull(getId()),
        guestId,
        action,
        documentId,
        replacementDocumentId,
        new Why(reason, requestedAt, requestChannel, note),
        contentType,
        sizeBytes,
        getCreatedBy().orElseThrow().getId(),
        getCreatedDate().orElseThrow().atZone(ZoneId.systemDefault()).toInstant());
  }

  @Override
  public String toString() {
    // Never the note: it is not logged.
    return "GuestDocumentActionJpaEntity[id=" + getId() + ", documentId=" + documentId + "]";
  }
}
