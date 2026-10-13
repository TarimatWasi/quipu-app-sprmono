package com.tarimatwasi.quipu.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.TestTables;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** The expenses table: the keyset index (TAR-176) and the approved categories (TAR-183). */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@ImportTestcontainers(PostgresContainers.class)
class ExpensesMigrationTest {

  private static final AtomicLong NEXT_ID = new AtomicLong(10_000);

  @Autowired JdbcTemplate jdbc;
  @Autowired DataSource dataSource;

  @BeforeEach
  void clearTheSharedTables() {
    TestTables.clear(jdbc);
  }

  @AfterEach
  void leaveTheSharedTablesClean() {
    TestTables.clear(jdbc);
  }

  @Test
  void expenses_haveAnIndexInTheOrderOfTheListing() {
    List<String> definitions =
        jdbc.queryForList(
            "SELECT pg_get_indexdef(indexrelid) FROM pg_index"
                + " WHERE indrelid = 'expenses'::regclass",
            String.class);

    assertThat(definitions)
        .anyMatch(definition -> definition.contains("(month, created_date, id)"));
  }

  /** TAR-183 (D-26): the base only accepts the four approved categories. */
  @ParameterizedTest
  @ValueSource(strings = {"UTILITIES", "MAINTENANCE", "CLEANING_SUPPLIES", "TAXES_FEES"})
  void expenses_acceptTheApprovedCategories(String category) {
    assertThat(insertExpense("public", category)).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"WATER", "ELECTRICITY", "INTERNET", "OTHER", "utilities"})
  void expenses_rejectTheOldCategories(String category) {
    assertThatThrownBy(() -> insertExpense("public", category))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_expenses_type");
  }

  /** TAR-183: V10 carries the rows of V9 to UTILITIES, OTHER included, and keeps the rest. */
  @Test
  void v10_movesTheOldCategoriesToUtilities() {
    String schema = "tar183_migration";
    jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    try {
      migrateTo(schema, "9");
      for (String old : List.of("WATER", "ELECTRICITY", "INTERNET", "OTHER")) {
        insertExpense(schema, old);
      }

      migrateTo(schema, "10");

      assertThat(
              jdbc.queryForList("SELECT DISTINCT type FROM " + schema + ".expenses", String.class))
          .containsExactly("UTILITIES");
      assertThat(jdbc.queryForObject("SELECT count(*) FROM " + schema + ".expenses", Integer.class))
          .isEqualTo(4);
    } finally {
      jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
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

  private int insertExpense(String schema, String category) {
    return jdbc.update(
        "INSERT INTO "
            + schema
            + ".expenses (id, type, amount, month, created_date, last_modified_date)"
            + " VALUES (?, ?, 10, DATE '2026-09-01', now(), now())",
        NEXT_ID.incrementAndGet(),
        category);
  }
}
