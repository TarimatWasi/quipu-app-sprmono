package com.tarimatwasi.quipu.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.support.PostgresContainers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * TAR-214: without app.migration.* (local, the tests and every deployment until the application
 * role exists) Flyway and the application share one connection, as before, and the privileges
 * script runs without granting anything.
 */
@SpringBootTest
@ImportTestcontainers(PostgresContainers.class)
class ApplicationRoleDefaultTest {

  @Autowired JdbcTemplate jdbc;

  @Test
  void flywayAndTheApplicationShareTheOwnerConnection() {
    String user = jdbc.queryForObject("SELECT current_user", String.class);

    assertThat(
            jdbc.queryForList(
                "SELECT DISTINCT tableowner FROM pg_tables WHERE schemaname = 'public'",
                String.class))
        .containsExactly(user);
  }

  @Test
  void thePrivilegesScriptRanAndGrantedNothing() {
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history"
                    + " WHERE description = 'application role privileges' AND success",
                Integer.class))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                    + " WHERE n.nspname = 'public' AND c.relacl IS NOT NULL",
                Integer.class))
        .isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_default_acl", Integer.class)).isZero();
  }
}
