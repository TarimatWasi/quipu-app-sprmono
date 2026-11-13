package com.tarimatwasi.quipu.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.support.PostgresContainers;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * TAR-377: a fresh database starts at V1 with the current model, without legacy data conversion.
 */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@ImportTestcontainers(PostgresContainers.class)
class BaselineMigrationTest {

  private static final String SCHEMA = "tar377_empty";

  @Autowired DataSource dataSource;

  private JdbcTemplate jdbc;

  @BeforeEach
  void migrateAnEmptySchema() {
    jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(SCHEMA)
        .createSchemas(true)
        .placeholders(Map.of("app_role", ""))
        .load()
        .migrate();
  }

  @AfterEach
  void dropOwnSchema() {
    jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
  }

  @Test
  void onlyV1IsAppliedAndItCreatesNoBusinessDataOrAccounts() {
    assertThat(
            jdbc.queryForList(
                "SELECT version FROM "
                    + SCHEMA
                    + ".flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank",
                String.class))
        .containsExactly("1");
    for (String table :
        new String[] {"users", "guests", "payments", "sessions", "refresh_tokens"}) {
      assertThat(jdbc.queryForObject("SELECT count(*) FROM " + SCHEMA + "." + table, Long.class))
          .as("no rows seeded in %s", table)
          .isZero();
    }
  }

  @Test
  void paymentProofsAndReviewsExistWithoutTheObsoletePaymentVoucherColumns() {
    assertThat(
            jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns"
                    + " WHERE table_schema = ? AND table_name = 'payments'",
                String.class,
                SCHEMA))
        .contains("submitted_at", "verified_amount")
        .doesNotContain(
            "voucher_key", "original_name", "content_type", "size_bytes", "payment_method");
    for (String table : new String[] {"payment_proofs", "payment_reviews", "refunds"}) {
      assertThat(jdbc.queryForObject("SELECT count(*) FROM " + SCHEMA + "." + table, Long.class))
          .as("current financial table %s exists and is empty", table)
          .isZero();
    }
  }
}
