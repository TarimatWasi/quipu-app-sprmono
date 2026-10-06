package com.tarimatwasi.quipu.shared.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.auth.adapter.out.persistence.UserJpaRepository;
import com.tarimatwasi.quipu.auth.domain.DocumentType;
import com.tarimatwasi.quipu.support.PostgresContainers;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;

/** The audit columns of the base entity are mapped from the database (QP-SPRMONO-DAT-01). */
@SpringBootTest
@ImportTestcontainers(PostgresContainers.class)
class AuditableEntityTest {

  @Autowired JdbcTemplate jdbc;
  @Autowired UserJpaRepository users;

  @Test
  void auditColumnsAreMappedOnALoadedEntity() {
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " must_change_password, status, created_by, last_modified_by)"
            + " VALUES (?, 'audit@example.test', 'DNI', '77777777', 'hash', 'GUEST', FALSE,"
            + " 'ACTIVE', 'creator', 'editor')",
        UUID.randomUUID());

    var entity = users.findByDocumentTypeAndDocumentNumber(DocumentType.DNI, "77777777");

    assertThat(entity)
        .hasValueSatisfying(
            e -> {
              assertThat(e.getCreatedDate()).isNotNull();
              assertThat(e.getLastModifiedDate()).isNotNull();
              assertThat(e.getCreatedBy()).isEqualTo("creator");
              assertThat(e.getLastModifiedBy()).isEqualTo("editor");
            });
  }
}
