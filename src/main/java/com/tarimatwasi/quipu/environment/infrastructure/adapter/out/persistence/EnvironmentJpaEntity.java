package com.tarimatwasi.quipu.environment.infrastructure.adapter.out.persistence;

import com.tarimatwasi.quipu.environment.domain.Environment;
import com.tarimatwasi.quipu.environment.domain.EnvironmentStatus;
import com.tarimatwasi.quipu.environment.domain.EnvironmentType;
import com.tarimatwasi.quipu.shared.adapter.out.persistence.AuditorJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.domain.AbstractAuditable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "environments")
public class EnvironmentJpaEntity extends AbstractAuditable<AuditorJpaEntity, Long> {

  private String code;

  @Enumerated(EnumType.STRING)
  private EnvironmentType type;

  @Enumerated(EnumType.STRING)
  private EnvironmentStatus status;

  @Version private long version;

  /** TAR-237 (A2.1): when it was deactivated; null while it is active. */
  @Column(name = "deactivated_at")
  private @Nullable Instant deactivatedAt;

  protected EnvironmentJpaEntity() {}

  EnvironmentJpaEntity(String code, EnvironmentType type) {
    this.code = code;
    this.type = type;
    this.status = EnvironmentStatus.ACTIVE;
  }

  void rename(String newCode) {
    this.code = newCode;
  }

  void retype(EnvironmentType newType) {
    this.type = newType;
  }

  /** TAR-237: a deactivation records when; a reactivation clears it. */
  void changeStatus(EnvironmentStatus newStatus, Instant at) {
    if (newStatus != this.status) {
      this.deactivatedAt = newStatus == EnvironmentStatus.INACTIVE ? at : null;
    }
    this.status = newStatus;
  }

  Environment toDomain() {
    // AbstractAuditable gives the creation as a LocalDateTime of the zone of the JVM.
    Instant createdAt =
        getCreatedDate()
            .map(created -> created.atZone(ZoneId.systemDefault()).toInstant())
            .orElse(Instant.EPOCH);
    return new Environment(
        Objects.requireNonNull(getId()), code, type, status, createdAt, deactivatedAt, version);
  }
}
