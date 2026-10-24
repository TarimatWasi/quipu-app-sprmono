package com.tarimatwasi.quipu.guest.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tarimatwasi.quipu.guest.domain.GuestConsent;
import com.tarimatwasi.quipu.guest.port.out.GuestConsentAlreadyExistsException;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** D-49 (TAR-137): a second consent of one version is reported as such, not as a failure. */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@ImportTestcontainers(PostgresContainers.class)
class OnboardingRepositoryAdapterTest {

  private static final String HASH = "a".repeat(64);

  @Autowired OnboardingRepositoryAdapter adapter;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate transaction;

  private final Long guest = TestIds.next();

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
    jdbc.update(
        "INSERT INTO guests (id, document_type, document_number, guest_type, status)"
            + " VALUES (?, 'DNI', '12345678', 'CONTRACT', 'ONBOARDING')",
        guest);
  }

  @AfterEach
  void tearDown() {
    TestTables.clear(jdbc);
  }

  @Test
  void addConsent_ofAVersionAlreadyRecorded_reportsTheConsent() {
    add(guest, "v1");

    assertThatThrownBy(() -> add(guest, "v1"))
        .isInstanceOf(GuestConsentAlreadyExistsException.class);
    assertThat(adapter.consentOf(guest, "v1")).isPresent();
  }

  @Test
  void addConsent_breakingAnotherRule_keepsTheFailure() {
    assertThatThrownBy(() -> add(TestIds.next(), "v1"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private void add(Long guestId, String version) {
    transaction.executeWithoutResult(
        status -> adapter.addConsent(new GuestConsent(guestId, version, Instant.now(), HASH)));
  }
}
