package com.tarimatwasi.quipu.db;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.support.PostgresContainers;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;

/** TAR-182 (V20): the stay columns leave guests after the last copy to stays (contract phase). */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@ImportTestcontainers(PostgresContainers.class)
class GuestStayColumnsMigrationTest {

  private static final AtomicLong NEXT_ID = new AtomicLong(60_000);

  @Autowired JdbcTemplate jdbc;
  @Autowired DataSource dataSource;

  /** Ids of the guests written before V20, each one a case of the contract phase. */
  private record Fixture(
      String schema, long withStay, long withoutStay, long partial, long contract, long stayId) {}

  /** (a) The complete guest without a stay gets it, with its own values and audit columns. */
  @Test
  void v20_copiesTheStayOfACompleteGuestWithoutOne() {
    inMigratedSchema(
        "tar182_contract_a",
        f -> {
          List<Map<String, Object>> copied = staysOf(f.schema(), f.withoutStay());
          assertThat(copied).hasSize(1);
          assertThat(copied.get(0))
              .containsEntry("start_date", "2026-10-10")
              .containsEntry("end_date", "2026-10-20")
              .containsEntry("version", 0L)
              .containsEntry("has_created_date", true)
              .containsEntry("has_last_modified_date", true)
              .containsEntry("created_by_id", null)
              .containsEntry("last_modified_by_id", null);
          assertThat((BigDecimal) copied.get(0).get("agreed_amount"))
              .isEqualByComparingTo("150.50");
          // The copied id comes from the sequence: the next one is still free.
          assertThat(
                  jdbc.queryForObject("SELECT nextval('" + f.schema() + ".stays_seq')", Long.class))
              .isGreaterThan((Long) copied.get(0).get("id"));
        });
  }

  /** (b) The guest that already had its stay keeps exactly that one. */
  @Test
  void v20_doesNotDuplicateAnExistingStay() {
    inMigratedSchema(
        "tar182_contract_b",
        f -> {
          List<Map<String, Object>> kept = staysOf(f.schema(), f.withStay());
          assertThat(kept).hasSize(1);
          assertThat(kept.get(0))
              .containsEntry("id", f.stayId())
              .containsEntry("start_date", "2026-09-01");
        });
  }

  /** (c) Partial data never formed a stay, nor do the data of a contract guest. */
  @Test
  void v20_discardsPartialDataWithoutFailing() {
    inMigratedSchema(
        "tar182_contract_c",
        f -> {
          assertThat(staysOf(f.schema(), f.partial())).isEmpty();
          assertThat(staysOf(f.schema(), f.contract())).isEmpty();
          assertThat(
                  jdbc.queryForObject(
                      "SELECT count(*) FROM " + f.schema() + ".stays", Integer.class))
              .isEqualTo(2);
        });
  }

  /** (d) The three columns and the two checks are gone from guests. */
  @Test
  void v20_dropsTheColumnsAndTheirChecks() {
    inMigratedSchema(
        "tar182_contract_d",
        f -> {
          assertThat(
                  jdbc.queryForList(
                      "SELECT column_name FROM information_schema.columns WHERE table_schema = ?"
                          + " AND table_name = 'guests'",
                      String.class,
                      f.schema()))
              .contains("guest_type")
              .doesNotContain("stay_start_date", "stay_end_date", "agreed_amount");
          assertThat(
                  jdbc.queryForList(
                      "SELECT conname FROM pg_constraint WHERE conrelid = ?::regclass",
                      String.class,
                      f.schema() + ".guests"))
              .contains("ck_guests_type")
              .doesNotContain("ck_guests_stay_dates", "ck_guests_agreed_amount");
        });
  }

  /** The application schema has already run V20. */
  @Test
  void guests_noLongerHaveTheStayColumns() {
    assertThat(
            jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public'"
                    + " AND table_name = 'guests'",
                String.class))
        .isNotEmpty()
        .doesNotContain("stay_start_date", "stay_end_date", "agreed_amount");
  }

  private void inMigratedSchema(String schema, Consumer<Fixture> check) {
    jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    try {
      migrateTo(schema, "10");
      // Copied by V11: it already has its stay before V20.
      long withStay =
          insertGuest(schema, "20000001", "TEMPORARY", "2026-09-01", "2026-09-10", "90");
      migrateTo(schema, "19");
      // Written by the application before V11 while the deployment was still running.
      long withoutStay =
          insertGuest(schema, "20000002", "TEMPORARY", "2026-10-10", "2026-10-20", "150.50");
      long partial = insertGuest(schema, "20000003", "TEMPORARY", "2026-10-10", null, "100");
      long contract =
          insertGuest(schema, "20000004", "CONTRACT", "2026-10-10", "2026-10-20", "100");
      Long stayId =
          jdbc.queryForObject(
              "SELECT id FROM " + schema + ".stays WHERE guest_id = ?", Long.class, withStay);

      migrateTo(schema, "20");

      check.accept(
          new Fixture(schema, withStay, withoutStay, partial, contract, requireNonNull(stayId)));
    } finally {
      jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  private List<Map<String, Object>> staysOf(String schema, long guestId) {
    return jdbc.queryForList(
        "SELECT id, start_date::text AS start_date, end_date::text AS end_date, agreed_amount,"
            + " version, created_date IS NOT NULL AS has_created_date,"
            + " last_modified_date IS NOT NULL AS has_last_modified_date, created_by_id,"
            + " last_modified_by_id FROM "
            + schema
            + ".stays WHERE guest_id = ? ORDER BY id",
        guestId);
  }

  private void migrateTo(String schema, String version) {
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .createSchemas(true)
        .target(version)
        .load()
        .migrate();
  }

  private long insertGuest(
      String schema, String number, String type, String start, String end, String amount) {
    long id = NEXT_ID.incrementAndGet();
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".guests (id, document_type, document_number, guest_type, status, stay_start_date,"
            + " stay_end_date, agreed_amount) VALUES (?, 'DNI', ?, ?, 'ACTIVE', ?::date, ?::date,"
            + " ?::numeric)",
        id,
        number,
        type,
        start,
        end,
        amount);
    return id;
  }
}
