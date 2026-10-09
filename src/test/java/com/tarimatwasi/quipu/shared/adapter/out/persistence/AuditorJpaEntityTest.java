package com.tarimatwasi.quipu.shared.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.environment.domain.EnvironmentType;
import com.tarimatwasi.quipu.environment.port.out.EnvironmentRepositoryPort;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.TestIds;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.data.jpa.domain.AbstractAuditable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The audit columns come from Spring Data's {@link AbstractAuditable} and the auditor is the
 * logged-in user, taken from the security context (QP-SPRMONO-DAT-01).
 */
@SpringBootTest
@ImportTestcontainers(PostgresContainers.class)
class AuditorJpaEntityTest {

  @Autowired JdbcTemplate jdbc;
  @Autowired EntityManager entityManager;
  @Autowired EnvironmentRepositoryPort environments;

  @AfterEach
  void clearTheSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void auditColumnsAreMappedOnALoadedEntity() {
    Long creator = insertUser("66666666");
    Long editor = insertUser("66666667");
    Long audited = TestIds.next();
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " must_change_password, status, created_by_id, last_modified_by_id)"
            + " VALUES (?, 'audit@example.test', 'DNI', '77777777', 'hash', 'GUEST', FALSE,"
            + " 'ACTIVE', ?, ?)",
        audited,
        creator,
        editor);

    // Read through the base type: the audit columns belong to it, whatever entity extends it.
    @SuppressWarnings("unchecked")
    var entity =
        (AbstractAuditable<AuditorJpaEntity, Long>)
            entityManager
                .createQuery(
                    "select u from UserJpaEntity u where u.documentNumber = :number", Object.class)
                .setParameter("number", "77777777")
                .getSingleResult();

    assertThat(entity.getCreatedDate()).isPresent();
    assertThat(entity.getLastModifiedDate()).isPresent();
    assertThat(entity.getCreatedBy()).map(AuditorJpaEntity::getId).contains(creator);
    assertThat(entity.getLastModifiedBy()).map(AuditorJpaEntity::getId).contains(editor);
  }

  @Test
  void aRowSavedByALoggedInUserRecordsThatUserAsItsAuditor() {
    Long user = insertUser("55555555");
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(user.toString(), null, List.of()));

    var saved = environments.insert("AUD-" + user, EnvironmentType.ROOM);

    assertThat(auditorOf("environments", saved.id(), "created_by_id")).isEqualTo(user);
    assertThat(auditorOf("environments", saved.id(), "last_modified_by_id")).isEqualTo(user);
  }

  @Test
  void aRowSavedWithoutASessionHasNoAuditor() {
    var saved = environments.insert("ANON-" + TestIds.next(), EnvironmentType.CABIN);

    assertThat(auditorOf("environments", saved.id(), "created_by_id")).isNull();
    assertThat(auditorOf("environments", saved.id(), "last_modified_by_id")).isNull();
  }

  @Test
  void aSessionNameThatIsNotAnIdIsNotAnAuditor() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken("not-a-number", null, List.of()));

    var saved = environments.insert("BAD-" + TestIds.next(), EnvironmentType.ROOM);

    assertThat(auditorOf("environments", saved.id(), "created_by_id")).isNull();
  }

  private Long insertUser(String documentNumber) {
    Long id = TestIds.next();
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " must_change_password, status) VALUES (?, ?, 'DNI', ?, 'hash', 'GUEST', FALSE,"
            + " 'ACTIVE')",
        id,
        documentNumber + "@example.test",
        documentNumber);
    return id;
  }

  private @Nullable Long auditorOf(String table, Long id, String column) {
    return jdbc.queryForObject(
        "SELECT " + column + " FROM " + table + " WHERE id = ?", Long.class, id);
  }
}
