package com.tarimatwasi.quipu.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.support.PostgresContainers;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;

/** TAR-182: the stays table of the temporary guests and the index of their listing. */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@ImportTestcontainers(PostgresContainers.class)
class StaysMigrationTest {

  @Autowired JdbcTemplate jdbc;
  @Autowired DataSource dataSource;

  /** Migrating an empty schema leaves an empty stays table whose sequence starts at 1. */
  @Test
  void migrations_createAnEmptyStaysTable() {
    String schema = "tar182_empty";
    jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    try {
      Flyway.configure()
          .dataSource(dataSource)
          .schemas(schema)
          .createSchemas(true)
          .load()
          .migrate();

      assertThat(jdbc.queryForObject("SELECT count(*) FROM " + schema + ".stays", Integer.class))
          .isZero();
      assertThat(jdbc.queryForObject("SELECT nextval('" + schema + ".stays_seq')", Long.class))
          .isEqualTo(1L);
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
}
