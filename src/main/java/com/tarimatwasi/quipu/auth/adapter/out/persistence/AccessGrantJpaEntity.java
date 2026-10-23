package com.tarimatwasi.quipu.auth.adapter.out.persistence;

import com.tarimatwasi.quipu.shared.access.AccessMode;
import com.tarimatwasi.quipu.shared.adapter.out.persistence.AuditorJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.domain.AbstractAuditable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * An access of a guest (RN-32). Only its creation goes through this entity; it is used or revoked
 * by conditional updates of {@link AccessGrantJpaRepository}.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "access_grants")
public class AccessGrantJpaEntity extends AbstractAuditable<AuditorJpaEntity, Long> {

  @Column(name = "guest_id")
  private Long guestId;

  @Enumerated(EnumType.STRING)
  private AccessMode mode;

  @Column(name = "token_hash")
  private String tokenHash;

  @Column(name = "expires_at")
  private Instant expiresAt;

  @Column(name = "used_at")
  private @Nullable Instant usedAt;

  @Column(name = "revoked_at")
  private @Nullable Instant revokedAt;

  protected AccessGrantJpaEntity() {}

  AccessGrantJpaEntity(Long guestId, AccessMode mode, String tokenHash, Instant expiresAt) {
    this.guestId = guestId;
    this.mode = mode;
    this.tokenHash = tokenHash;
    this.expiresAt = expiresAt;
  }

  String tokenHash() {
    return tokenHash;
  }

  Instant expiresAt() {
    return expiresAt;
  }

  @Override
  public String toString() {
    // Not the hash: an entity that reaches a log should not carry it.
    return "AccessGrantJpaEntity[id=" + getId() + ", guestId=" + guestId + ", mode=" + mode + "]";
  }
}
