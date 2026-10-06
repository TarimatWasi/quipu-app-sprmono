package com.tarimatwasi.quipu.environment.adapter.out.persistence;

import com.tarimatwasi.quipu.environment.domain.Environment;
import com.tarimatwasi.quipu.environment.domain.EnvironmentStatus;
import com.tarimatwasi.quipu.environment.domain.EnvironmentType;
import com.tarimatwasi.quipu.shared.adapter.out.persistence.AuditableEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;

@Entity
@Table(name = "environments")
public class EnvironmentJpaEntity extends AuditableEntity {

  @Id private UUID id;

  private String code;

  @Enumerated(EnumType.STRING)
  private EnvironmentType type;

  @Enumerated(EnumType.STRING)
  private EnvironmentStatus status;

  @Version private long version;

  protected EnvironmentJpaEntity() {}

  EnvironmentJpaEntity(UUID id, String code, EnvironmentType type) {
    this.id = id;
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

  Environment toDomain() {
    return new Environment(id, code, type, status);
  }
}
