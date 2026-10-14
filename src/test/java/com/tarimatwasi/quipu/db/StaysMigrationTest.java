package com.tarimatwasi.quipu.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.support.PostgresContainers;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;

/** TAR-182 (V11): the stays table and the copy of the stay data the guests already had. */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@ImportTestcontainers(PostgresContainers.class)
class StaysMigrationTest {

  private static final AtomicLong NEXT_ID = new AtomicLong(50_000);

  @Autowired JdbcTemplate jdbc;
  @Autowired DataSource dataSource;

  /** One row per temporary guest with the three data; no row for the rest, nothing lost. */
  @Test
  void v11_copiesTheStayOfEachTemporaryGuestThatHadAllThreeData() {
    String schema = "tar182_migration";
    jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    try {
      migrateTo(schema, "10");
      long complete =
          insertGuest(schema, "10000001", "TEMPORARY", "2026-10-10", "2026-10-20", "150.50");
      long other = insertGuest(schema, "10000002", "TEMPORARY", "2026-11-01", "2026-11-05", "80");
      insertGuest(schema, "10000003", "TEMPORARY", null, null, null);
      insertGuest(schema, "10000004", "TEMPORARY", "2026-10-10", null, "100");
      insertGuest(schema, "10000005", "TEMPORARY", "2026-10-10", "2026-10-20", null);
      insertGuest(schema, "10000006", "CONTRACT", "2026-10-10", "2026-10-20", "100");
      insertGuest(schema, "10000007", "CONTRACT", null, null, null);

      migrateTo(schema, "11");

      List<Map<String, Object>> stays =
          jdbc.queryForList(
              "SELECT guest_id, start_date::text AS start_date, end_date::text AS end_date,"
                  + " agreed_amount, version FROM "
                  + schema
                  + ".stays ORDER BY guest_id");
      assertThat(stays).hasSize(2);
      assertThat(stays.get(0))
          .containsEntry("guest_id", complete)
          .containsEntry("start_date", "2026-10-10")
          .containsEntry("end_date", "2026-10-20")
          .containsEntry("version", 0L);
      assertThat((BigDecimal) stays.get(0).get("agreed_amount")).isEqualByComparingTo("150.50");
      assertThat(stays.get(1)).containsEntry("guest_id", other);
      // The old columns stay for the instance still running during the deployment.
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM " + schema + ".guests WHERE stay_start_date IS NOT NULL",
                  Integer.class))
          .isEqualTo(5);
      // The sequence continues after the copied rows, with no id taken twice.
      assertThat(jdbc.queryForObject("SELECT nextval('" + schema + ".stays_seq')", Long.class))
          .isGreaterThan(
              jdbc.queryForObject("SELECT max(id) FROM " + schema + ".stays", Long.class));
    } finally {
      jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  @Test
  void v11_withoutGuestsCreatesAnEmptyTable() {
    String schema = "tar182_empty";
    jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    try {
      migrateTo(schema, "11");

      assertThat(jdbc.queryForObject("SELECT count(*) FROM " + schema + ".stays", Integer.class))
          .isZero();
    } finally {
      jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  @Test
  void stays_haveTheIndexOfTheListing() {
    List<String> definitions =
        jdbc.queryForList(
            "SELECT pg_get_indexdef(indexrelid) FROM pg_index WHERE indrelid = 'stays'::regclass",
            String.class);

    assertThat(definitions)
        .anyMatch(definition -> definition.contains("(guest_id, start_date DESC, id DESC)"));
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
