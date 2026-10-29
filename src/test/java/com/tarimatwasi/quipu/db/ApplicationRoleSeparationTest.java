package com.tarimatwasi.quipu.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tarimatwasi.quipu.support.RoleSeparatedPostgres;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * TAR-214: with app.migration.* set, Flyway migrates as the owner of the tables and the application
 * connects as the application role; the application starts (Hibernate validates the schema and the
 * first ADMIN is created) and works with the privileges the migrations granted.
 */
@SpringBootTest
class ApplicationRoleSeparationTest {

  private static final String URL = RoleSeparatedPostgres.newDatabase();

  @DynamicPropertySource
  static void separatedRoles(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> URL);
    registry.add("spring.datasource.username", () -> RoleSeparatedPostgres.APP);
    registry.add("spring.datasource.password", () -> RoleSeparatedPostgres.APP_PASSWORD);
    registry.add("app.migration.url", () -> URL);
    registry.add("app.migration.user", () -> RoleSeparatedPostgres.OWNER);
    registry.add("app.migration.password", () -> RoleSeparatedPostgres.OWNER_PASSWORD);
    registry.add("app.migration.app-role", () -> RoleSeparatedPostgres.APP);
  }

  @Autowired JdbcTemplate jdbc;

  @Test
  void theApplicationConnectsAsTheApplicationRole() {
    assertThat(jdbc.queryForObject("SELECT current_user", String.class))
        .isEqualTo(RoleSeparatedPostgres.APP);
  }

  @Test
  void flywayMigratedAsTheOwner() {
    assertThat(
            jdbc.queryForList(
                "SELECT DISTINCT tableowner FROM pg_tables WHERE schemaname = 'public'",
                String.class))
        .containsExactly(RoleSeparatedPostgres.OWNER);
  }

  @Test
  void theApplicationStartedAndCreatedTheFirstAdministrator() {
    // AdminBootstrap of the local profile inserted it at startup, as the application role
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE role = 'ADMIN' AND created_by_id IS NULL",
                Integer.class))
        .isOne();
  }

  @Test
  void theEvidenceCannotBeTruncatedNorStrippedOfItsTriggerByTheApplication() {
    assertDenied("TRUNCATE TABLE guest_document_actions");
    assertDenied("DROP TRIGGER trg_guest_document_actions_immutable ON guest_document_actions");
  }

  private void assertDenied(String sql) {
    assertThatThrownBy(() -> jdbc.execute(sql))
        .as(sql)
        .rootCause()
        .isInstanceOfSatisfying(
            SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
  }
}
