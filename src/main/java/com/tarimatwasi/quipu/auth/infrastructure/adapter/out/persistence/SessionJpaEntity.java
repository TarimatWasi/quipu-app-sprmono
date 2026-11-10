package com.tarimatwasi.quipu.auth.infrastructure.adapter.out.persistence;

import com.tarimatwasi.quipu.shared.adapter.out.persistence.AuditorJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Table;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.domain.AbstractAuditable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * TAR-228: a session of an account. Only its creation goes through this entity; it is used, updated
 * and closed by conditional updates of {@link SessionJpaRepository}.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "sessions")
public class SessionJpaEntity extends AbstractAuditable<AuditorJpaEntity, Long> {

  @Column(name = "user_id")
  private Long userId;

  @Column(name = "sid_hash")
  private String sidHash;

  @Column(name = "security_version")
  private long securityVersion;

  private boolean temporary;

  @Column(name = "absolute_expires_at")
  private Instant absoluteExpiresAt;

  @Column(name = "last_used_at")
  private Instant lastUsedAt;

  @Column(name = "revoked_at")
  private @Nullable Instant revokedAt;

  protected SessionJpaEntity() {}

  SessionJpaEntity(
      Long userId,
      String sidHash,
      long securityVersion,
      boolean temporary,
      Instant absoluteExpiresAt,
      Instant now) {
    this.userId = userId;
    this.sidHash = sidHash;
    this.securityVersion = securityVersion;
    this.temporary = temporary;
    this.absoluteExpiresAt = absoluteExpiresAt;
    this.lastUsedAt = now;
  }

  Long userId() {
    return userId;
  }

  String sidHash() {
    return sidHash;
  }

  long securityVersion() {
    return securityVersion;
  }

  boolean temporary() {
    return temporary;
  }

  Instant absoluteExpiresAt() {
    return absoluteExpiresAt;
  }

  Instant lastUsedAt() {
    return lastUsedAt;
  }

  @Nullable Instant revokedAt() {
    return revokedAt;
  }

  @Override
  public String toString() {
    // Not the hash of the sid: an entity that reaches a log should not carry it.
    return "SessionJpaEntity[id=" + getId() + ", userId=" + userId + "]";
  }
}
