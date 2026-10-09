package com.tarimatwasi.quipu.shared.adapter.out.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/**
 * The user as seen by the audit columns: Spring Data's {@code AbstractAuditable} keeps the auditor
 * as an association to an entity, and this one lets every module point at the author of a change
 * without depending on the auth module's own entity. It is read-only and carries only the id.
 */
@Entity
@Immutable
@Table(name = "users")
public class AuditorJpaEntity {

  @Id private Long id;

  protected AuditorJpaEntity() {}

  public Long getId() {
    return id;
  }
}
