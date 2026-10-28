package com.tarimatwasi.quipu.guest.infrastructure.adapter.out.persistence;

import com.tarimatwasi.quipu.shared.adapter.out.persistence.AuditorJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Table;
import org.springframework.data.jpa.domain.AbstractAuditable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** An object of the store that no row references any more and is still to be removed (RN-37). */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "guest_document_purges")
public class GuestDocumentPurgeJpaEntity extends AbstractAuditable<AuditorJpaEntity, Long> {

  @Column(name = "storage_key")
  private String storageKey;

  protected GuestDocumentPurgeJpaEntity() {}

  GuestDocumentPurgeJpaEntity(String storageKey) {
    this.storageKey = storageKey;
  }

  String storageKey() {
    return storageKey;
  }

  @Override
  public String toString() {
    // Never the key: an entity that reaches a log should not carry it.
    return "GuestDocumentPurgeJpaEntity[id=" + getId() + "]";
  }
}
