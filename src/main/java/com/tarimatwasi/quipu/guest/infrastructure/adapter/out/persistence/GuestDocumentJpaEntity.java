package com.tarimatwasi.quipu.guest.infrastructure.adapter.out.persistence;

import com.tarimatwasi.quipu.guest.domain.GuestDocument;
import com.tarimatwasi.quipu.shared.adapter.out.persistence.AuditorJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Table;
import java.time.ZoneId;
import java.util.Objects;
import org.hibernate.annotations.Immutable;
import org.springframework.data.jpa.domain.AbstractAuditable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** A document of the onboarding (RN-37): inserted once, never updated nor deleted. */
@Entity
@Immutable
@EntityListeners(AuditingEntityListener.class)
@Table(name = "guest_documents")
public class GuestDocumentJpaEntity extends AbstractAuditable<AuditorJpaEntity, Long> {

  @Column(name = "guest_id")
  private Long guestId;

  @Column(name = "storage_key")
  private String storageKey;

  @Column(name = "original_name")
  private String originalName;

  @Column(name = "content_type")
  private String contentType;

  @Column(name = "size_bytes")
  private long sizeBytes;

  protected GuestDocumentJpaEntity() {}

  GuestDocumentJpaEntity(
      Long guestId, String storageKey, String originalName, String contentType, long sizeBytes) {
    this.guestId = guestId;
    this.storageKey = storageKey;
    this.originalName = originalName;
    this.contentType = contentType;
    this.sizeBytes = sizeBytes;
  }

  GuestDocument toDomain() {
    return new GuestDocument(
        Objects.requireNonNull(getId()),
        guestId,
        storageKey,
        originalName,
        contentType,
        sizeBytes,
        getCreatedDate().orElseThrow().atZone(ZoneId.systemDefault()).toInstant());
  }

  @Override
  public String toString() {
    // Neither the name nor the key: an entity that reaches a log should not carry them.
    return "GuestDocumentJpaEntity[id=" + getId() + ", guestId=" + guestId + "]";
  }
}
