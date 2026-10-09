package com.tarimatwasi.quipu.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.tarimatwasi.quipu.auth.adapter.in.bootstrap.AdminBootstrap;
import com.tarimatwasi.quipu.auth.port.in.PasswordRecoveryUseCase;
import com.tarimatwasi.quipu.auth.port.out.PasswordResetMailPort;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.TestTables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The users table and the first-ADMIN bootstrap against a real PostgreSQL. The container is shared
 * by all integration tests, so each test starts from an empty table and runs the bootstrap itself.
 */
@SpringBootTest(
    properties = {"app.admin.document-number=12345678", "app.admin.email=admin@example.com"})
@ImportTestcontainers(PostgresContainers.class)
class UsersMigrationTest {

  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired AdminBootstrap adminBootstrap;
  @Autowired PasswordRecoveryUseCase recovery;
  @MockitoBean PasswordResetMailPort mail;

  @BeforeEach
  void emptyUsers() {
    TestTables.clear(jdbc);
  }

  @Test
  void bootstrapCreatesTheAdminWithoutAKnownPasswordAndTheLinkChoosesIt() {
    adminBootstrap.run(new DefaultApplicationArguments());

    var mustChange =
        jdbc.queryForObject(
            "SELECT must_change_password FROM users"
                + " WHERE document_number = '12345678' AND role = 'ADMIN'",
            Boolean.class);
    assertThat(mustChange).isTrue();
    var code = ArgumentCaptor.forClass(String.class);
    verify(mail).sendResetLink(eq("admin@example.com"), code.capture(), any());

    recovery.resetPassword(code.getValue(), "Elegida12345");

    var hash =
        jdbc.queryForObject(
            "SELECT password_hash FROM users WHERE document_number = '12345678'", String.class);
    assertThat(passwordEncoder.matches("Elegida12345", hash)).isTrue();
  }

  @Test
  void bootstrapDoesNotCreateAnotherAdminOnTheNextStartup() {
    adminBootstrap.run(new DefaultApplicationArguments());
    adminBootstrap.run(new DefaultApplicationArguments());

    var admins = jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
    assertThat(admins).isEqualTo(1);
  }

  @Test
  void migrationsSeedNoUsers() {
    var users = jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class);

    assertThat(users).isZero();
  }

  @Test
  void loginIdentifierIsUnique() {
    var count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.table_constraints "
                + "WHERE table_name = 'users' AND constraint_type = 'UNIQUE'",
            Integer.class);

    assertThat(count).isGreaterThanOrEqualTo(1);
  }
}
