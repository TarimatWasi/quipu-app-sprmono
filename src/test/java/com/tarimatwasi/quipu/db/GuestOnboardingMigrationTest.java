package com.tarimatwasi.quipu.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * TAR-137 (V16): a consent and a document are evidence; the database refuses to change them. (V17,
 * D-49): a guest accepts each version of the text once.
 */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@ImportTestcontainers(PostgresContainers.class)
class GuestOnboardingMigrationTest {

  @Autowired JdbcTemplate jdbc;

  private final Long guest = TestIds.next();

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
    jdbc.update(
        "INSERT INTO guests (id, document_type, document_number, guest_type, status)"
            + " VALUES (?, 'DNI', '12345678', 'CONTRACT', 'ACTIVE')",
        guest);
    jdbc.update(
        "INSERT INTO guest_consents (id, guest_id, consent_version, accepted_at, text_sha256)"
            + " VALUES (1, ?, 'v1', now(), repeat('a', 64))",
        guest);
    jdbc.update(
        "INSERT INTO guest_documents (id, guest_id, storage_key, original_name, content_type,"
            + " size_bytes) VALUES (1, ?, 'guest-documents/k', 'd.pdf', 'application/pdf', 10)",
        guest);
  }

  @AfterEach
  void tearDown() {
    TestTables.clear(jdbc);
  }

  @Test
  void aConsentIsNeitherUpdatedNorDeleted() {
    assertThatThrownBy(() -> jdbc.update("UPDATE guest_consents SET consent_version = 'v2'"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("guest_consents");
    assertThatThrownBy(() -> jdbc.update("DELETE FROM guest_consents"))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void aDocumentIsNeitherUpdatedNorDeleted() {
    assertThatThrownBy(() -> jdbc.update("UPDATE guest_documents SET original_name = 'x.pdf'"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("guest_documents");
    assertThatThrownBy(() -> jdbc.update("DELETE FROM guest_documents"))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void aGuestAcceptsEachVersionOnce() {
    assertThatThrownBy(() -> insertConsent(2L, "v1"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("uq_guest_consents_guest_version");
  }

  @Test
  void anotherVersionIsAcceptedAgain() {
    insertConsent(2L, "v2");

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM guest_consents WHERE guest_id = ?", Long.class, guest))
        .isEqualTo(2L);
  }

  private void insertConsent(Long id, String version) {
    jdbc.update(
        "INSERT INTO guest_consents (id, guest_id, consent_version, accepted_at, text_sha256)"
            + " VALUES (?, ?, ?, now(), repeat('a', 64))",
        id,
        guest,
        version);
  }
}
