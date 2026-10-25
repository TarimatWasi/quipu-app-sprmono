package com.tarimatwasi.quipu.guest.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tarimatwasi.quipu.guest.domain.Guest;
import com.tarimatwasi.quipu.guest.domain.GuestConsent;
import com.tarimatwasi.quipu.guest.domain.GuestDocument;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.Channel;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.Kind;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.Reason;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.Why;
import com.tarimatwasi.quipu.guest.domain.GuestStatus;
import com.tarimatwasi.quipu.guest.port.out.GuestConsentAlreadyExistsException;
import com.tarimatwasi.quipu.guest.port.out.GuestDocumentActionExistsException;
import com.tarimatwasi.quipu.guest.port.out.GuestDocumentAlreadyExistsException;
import com.tarimatwasi.quipu.guest.port.out.GuestEmailAlreadyExistsException;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import java.time.Instant;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * TAR-207: the unique rule that broke is found by the name the server gives, not by the wording of
 * its message. The server here answers in Spanish ({@code «name»}, not {@code "name"}), which the
 * name extractor of Hibernate does not read.
 */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@ImportTestcontainers(ViolatedConstraintTest.SpanishPostgres.class)
class ViolatedConstraintTest {

  /**
   * A server that answers in Spanish. The Debian image carries the Spanish catalogs but only the C
   * and en_US locales, so lc_messages cannot name es_ES. GNU gettext gives the LANGUAGE variable
   * priority over lc_messages whenever that is not C, so lc_messages=en_US.utf8 only switches the
   * translations on and LANGUAGE=es picks Spanish; theServerAnswersInSpanish checks it.
   */
  interface SpanishPostgres {
    @Container @ServiceConnection
    PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer("postgres:17")
            .withEnv("LANGUAGE", "es")
            .withCommand("postgres", "-c", "lc_messages=en_US.utf8")
            .waitingFor(Wait.forLogMessage(".*listo para aceptar conexiones.*\\s", 2));
  }

  @Autowired OnboardingRepositoryAdapter onboarding;
  @Autowired GuestRepositoryAdapter guests;
  @Autowired GuestDocumentActionRepositoryAdapter actions;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate transaction;

  private final Long admin = TestIds.next();
  private final Long guest = TestIds.next();
  private final Long other = TestIds.next();

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " must_change_password, status)"
            + " VALUES (?, NULL, 'DNI', '00000001', 'hash', 'ADMIN', FALSE, 'ACTIVE')",
        admin);
    jdbc.update(
        "INSERT INTO guests (id, document_type, document_number, guest_type, status, email)"
            + " VALUES (?, 'DNI', '12345678', 'CONTRACT', 'ACTIVE', 'ana@example.test')",
        guest);
    jdbc.update(
        "INSERT INTO guests (id, document_type, document_number, guest_type, status)"
            + " VALUES (?, 'DNI', '87654321', 'CONTRACT', 'ACTIVE')",
        other);
    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken(admin.toString(), "", "ROLE_ADMIN"));
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
    TestTables.clear(jdbc);
  }

  @Test
  void theServerAnswersInSpanish() {
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO guests (id, document_type, document_number, guest_type, status)"
                        + " VALUES (?, 'DNI', '12345678', 'CONTRACT', 'ACTIVE')",
                    TestIds.next()))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("llave duplicada");
  }

  @Test
  void aSecondConsentOfTheVersionIsReportedAsSuch() {
    addConsent();

    assertThatThrownBy(this::addConsent).isInstanceOf(GuestConsentAlreadyExistsException.class);
  }

  @Test
  void anEmailOfAnotherGuestIsReportedAsTheEmail() {
    Guest found = guests.findById(other).orElseThrow();

    assertThatThrownBy(() -> save(found, "ANA@example.test", found.documentNumber(), null))
        .isInstanceOf(GuestEmailAlreadyExistsException.class);
  }

  @Test
  void theDocumentOfAnotherGuestIsReportedAsTheDocument() {
    Guest found = guests.findById(other).orElseThrow();

    assertThatThrownBy(() -> save(found, found.email(), "12345678", null))
        .isInstanceOf(GuestDocumentAlreadyExistsException.class);
  }

  /** LOW of the security review: a CHECK is neither a repeated document nor a repeated email. */
  @Test
  void anotherRuleKeepsTheFailure() {
    Guest found = guests.findById(other).orElseThrow();

    assertThatThrownBy(
            () -> save(found, found.email(), found.documentNumber(), GuestStatus.INACTIVE))
        .isInstanceOf(DataIntegrityViolationException.class)
        .isNotInstanceOf(GuestDocumentAlreadyExistsException.class)
        .isNotInstanceOf(GuestEmailAlreadyExistsException.class);
  }

  private void save(
      Guest found,
      @Nullable String email,
      String documentNumber,
      @Nullable GuestStatus statusBeforeInactive) {
    Guest changed =
        new Guest(
            found.id(),
            found.documentType(),
            documentNumber,
            found.fullName(),
            email,
            found.phone(),
            found.type(),
            found.status(),
            statusBeforeInactive,
            found.personalDataCompletedAt(),
            found.documentsStepCompletedAt(),
            found.emergencyContact());
    transaction.execute(status -> guests.save(changed));
  }

  @Test
  void aSecondActionOnTheDocumentIsReportedAsSuch() {
    addAction();

    assertThatThrownBy(this::addAction).isInstanceOf(GuestDocumentActionExistsException.class);
  }

  private void addConsent() {
    transaction.executeWithoutResult(
        status ->
            onboarding.addConsent(new GuestConsent(guest, "v1", Instant.now(), "a".repeat(64))));
  }

  private void addAction() {
    var document =
        new GuestDocument(1L, guest, "guest-documents/1", "d.pdf", "image/png", 42, Instant.now());
    var why = new Why(Reason.ARCO_REQUEST, LocalDate.parse("2026-01-15"), Channel.OTHER, "ticket");
    transaction.executeWithoutResult(
        status -> actions.addAction(Kind.DELETE, document, null, why, admin, Instant.now()));
  }
}
