package com.tarimatwasi.quipu.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tarimatwasi.quipu.support.PostgresContainers;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** TAR-40: the contracts table, its indexes and what the base itself refuses. */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@ImportTestcontainers(PostgresContainers.class)
class ContractsMigrationTest {

  private static final AtomicLong NEXT_ID = new AtomicLong(70_000);

  @Autowired JdbcTemplate jdbc;

  /** A contract takes its id from contracts_seq and starts ACTIVE and not finished. */
  @Test
  void contracts_takeTheirIdFromTheSequenceAndStartActive() {
    long guest = insertGuest("20000001");
    long environment = insertEnvironment("301");
    try {
      jdbc.update(
          "INSERT INTO contracts (id, guest_id, environment_id, start_date, monthly_amount,"
              + " payment_day, grace_days, file_key, original_name, size_bytes)"
              + " VALUES (nextval('contracts_seq'), ?, ?, '2026-10-01', 800, 5, 3,"
              + " 'contracts/a.pdf', 'a.pdf', 10)",
          guest,
          environment);

      assertThat(
              jdbc.queryForMap(
                  "SELECT status, version, terminated_at FROM contracts WHERE guest_id = ?", guest))
          .containsEntry("status", "ACTIVE")
          .containsEntry("version", 0L)
          .containsEntry("terminated_at", null);
    } finally {
      jdbc.execute("TRUNCATE TABLE contracts, guests, environments CASCADE");
    }
  }

  @Test
  void contracts_haveTheIndexesOfTheLookupsAndTheListing() {
    List<String> definitions =
        jdbc.queryForList(
            "SELECT pg_get_indexdef(indexrelid) FROM pg_index WHERE indrelid = 'contracts'::regclass",
            String.class);

    assertThat(definitions)
        .anyMatch(definition -> definition.contains("(guest_id)"))
        .anyMatch(definition -> definition.contains("(environment_id)"))
        .anyMatch(definition -> definition.contains("(created_date DESC, id DESC)"));
    // No partial unique index by status: it would keep an environment busy after its contract
    // expired.
    assertThat(definitions)
        .noneMatch(definition -> definition.contains("UNIQUE") && !definition.contains("_pkey"));
  }

  @Test
  void contracts_idComesFromASequenceThatGrowsBy50() {
    assertThat(
            jdbc.queryForObject(
                "SELECT increment_by FROM pg_sequences WHERE sequencename = 'contracts_seq'",
                Long.class))
        .isEqualTo(50L);
  }

  @Test
  void contracts_refuseTheValuesTheDomainRefuses() {
    long guest = insertGuest("20000009");
    long environment = insertEnvironment("399");
    try {
      refused(
          guest, environment, new Terms("2026-10-01", "2026-10-01", "800", 5, 3, "ACTIVE"), "end");
      refused(guest, environment, new Terms("2026-10-01", null, "0", 5, 3, "ACTIVE"), "amount");
      refused(guest, environment, new Terms("2026-10-01", null, "800", 0, 3, "ACTIVE"), "day 0");
      refused(guest, environment, new Terms("2026-10-01", null, "800", 32, 3, "ACTIVE"), "day 32");
      refused(guest, environment, new Terms("2026-10-01", null, "800", 5, 32, "ACTIVE"), "grace");
      refused(guest, environment, new Terms("2026-10-01", null, "800", 5, 3, "EXPIRED"), "EXPIRED");
    } finally {
      jdbc.execute("TRUNCATE TABLE contracts, guests, environments CASCADE");
    }
  }

  @Test
  void contracts_allowTwoRowsForOneEnvironmentBecauseTheApplicationDecidesRn20() {
    long guest = insertGuest("20000009");
    long environment = insertEnvironment("399");
    try {
      long first =
          insertContract(guest, environment, new Terms("2026-10-01", null, "800", 5, 3, "ACTIVE"));
      // A finished contract also says when (terminated_at).
      jdbc.update(
          "UPDATE contracts SET status = 'TERMINATED', terminated_at = now() WHERE id = ?", first);
      insertContract(guest, environment, new Terms("2026-10-01", null, "800", 5, 3, "ACTIVE"));
    } finally {
      jdbc.execute("TRUNCATE TABLE contracts, guests, environments CASCADE");
    }
  }

  /** TAR-194: no trigger fills the instant a contract was finished; the CHECK alone guards it. */
  @Test
  void contracts_haveNoTriggerThatFillsTheInstantAFinishedContractWasFinished() {
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM pg_trigger WHERE tgrelid = 'public.contracts'::regclass"
                    + " AND tgname = 'trg_contracts_fill_terminated_at'",
                Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT to_regprocedure('public.contracts_fill_terminated_at()') IS NULL",
                Boolean.class))
        .isTrue();
  }

  @Test
  void contracts_refuseToFinishWithoutTheInstantItWasFinished() {
    long guest = insertGuest("20000009");
    long environment = insertEnvironment("399");
    try {
      long id =
          insertContract(guest, environment, new Terms("2026-10-01", null, "800", 5, 3, "ACTIVE"));

      assertThatThrownBy(
              () -> jdbc.update("UPDATE contracts SET status = 'TERMINATED' WHERE id = ?", id))
          .as("finishing an active contract")
          .isInstanceOf(DataIntegrityViolationException.class)
          .hasMessageContaining("ck_contracts_terminated_at");
      assertThatThrownBy(
              () ->
                  insertContract(
                      guest, environment, new Terms("2026-10-01", null, "800", 5, 3, "TERMINATED")))
          .as("inserting a finished contract")
          .isInstanceOf(DataIntegrityViolationException.class)
          .hasMessageContaining("ck_contracts_terminated_at");
    } finally {
      jdbc.execute("TRUNCATE TABLE contracts, guests, environments CASCADE");
    }
  }

  private void refused(long guest, long environment, Terms terms, String why) {
    assertThatThrownBy(() -> insertContract(guest, environment, terms))
        .as(why)
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  /** The columns the checks vary; the file columns are always the same. */
  private record Terms(
      String start,
      @Nullable String end,
      String amount,
      int paymentDay,
      int graceDays,
      String status) {}

  private long insertContract(long guest, long environment, Terms terms) {
    return jdbc.queryForObject(
        "INSERT INTO contracts (id, guest_id, environment_id, start_date, end_date, monthly_amount,"
            + " payment_day, grace_days, file_key, original_name, size_bytes, status)"
            + " VALUES (nextval('contracts_seq'), ?, ?, ?::date, ?::date, ?::numeric, ?, ?,"
            + " 'contracts/a.pdf', 'a.pdf', 10, ?) RETURNING id",
        Long.class,
        guest,
        environment,
        terms.start(),
        terms.end(),
        terms.amount(),
        terms.paymentDay(),
        terms.graceDays(),
        terms.status());
  }

  private long insertGuest(String number) {
    long id = NEXT_ID.incrementAndGet();
    jdbc.update(
        "INSERT INTO guests (id, document_type, document_number, guest_type, status)"
            + " VALUES (?, 'DNI', ?, 'CONTRACT', 'ACTIVE')",
        id,
        number);
    return id;
  }

  private long insertEnvironment(String code) {
    long id = NEXT_ID.incrementAndGet();
    jdbc.update("INSERT INTO environments (id, code, type) VALUES (?, ?, 'ROOM')", id, code);
    return id;
  }
}
