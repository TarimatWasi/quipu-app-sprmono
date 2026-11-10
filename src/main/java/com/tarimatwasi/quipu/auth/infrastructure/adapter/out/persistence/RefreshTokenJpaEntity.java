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
 * TAR-228: a refresh token of a session, as the SHA-256 of its secret. Rotated by a conditional
 * update of {@link RefreshTokenJpaRepository}.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "refresh_tokens")
public class RefreshTokenJpaEntity extends AbstractAuditable<AuditorJpaEntity, Long> {

  @Column(name = "session_id")
  private Long sessionId;

  @Column(name = "token_hash")
  private String tokenHash;

  @Column(name = "issued_at")
  private Instant issuedAt;

  @Column(name = "rotated_at")
  private @Nullable Instant rotatedAt;

  protected RefreshTokenJpaEntity() {}

  RefreshTokenJpaEntity(Long sessionId, String tokenHash, Instant issuedAt) {
    this.sessionId = sessionId;
    this.tokenHash = tokenHash;
    this.issuedAt = issuedAt;
  }

  Long sessionId() {
    return sessionId;
  }

  @Nullable Instant rotatedAt() {
    return rotatedAt;
  }

  @Override
  public String toString() {
    // Not the hash: an entity that reaches a log should not carry it.
    return "RefreshTokenJpaEntity[id=" + getId() + ", sessionId=" + sessionId + "]";
  }
}
