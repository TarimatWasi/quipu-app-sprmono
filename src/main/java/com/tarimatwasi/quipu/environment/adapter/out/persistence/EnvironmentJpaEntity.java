package com.tarimatwasi.quipu.environment.adapter.out.persistence;

import com.tarimatwasi.quipu.environment.domain.Environment;
import com.tarimatwasi.quipu.environment.domain.EnvironmentStatus;
import com.tarimatwasi.quipu.environment.domain.EnvironmentType;
import com.tarimatwasi.quipu.shared.adapter.out.persistence.AuditorJpaEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.Objects;
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

  void changeStatus(EnvironmentStatus newStatus) {
    this.status = newStatus;
  }

  Environment toDomain() {
    return new Environment(Objects.requireNonNull(getId()), code, type, status);
  }
}
