package com.tarimatwasi.quipu.db;

import static com.tarimatwasi.quipu.support.RoleSeparatedPostgres.APP;
import static com.tarimatwasi.quipu.support.RoleSeparatedPostgres.HEIR;
import static com.tarimatwasi.quipu.support.RoleSeparatedPostgres.OWNER;
import static com.tarimatwasi.quipu.support.RoleSeparatedPostgres.OWNER_PASSWORD;
import static com.tarimatwasi.quipu.support.RoleSeparatedPostgres.WRITER;
import static com.tarimatwasi.quipu.support.RoleSeparatedPostgres.asApp;
import static com.tarimatwasi.quipu.support.RoleSeparatedPostgres.asOwner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tarimatwasi.quipu.support.RoleSeparatedPostgres;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * TAR-214: the migrations, run by the owner of the tables, grant the application role what the
 * application needs and no more. The evidence of guest_document_actions can be inserted and read by
 * the application role, but not updated, deleted, truncated or stripped of its triggers. Without an
 * application role the migrations grant nothing, as before.
 */
class ApplicationRoleMigrationTest {

  private static final String INSUFFICIENT_PRIVILEGE = "42501";
  private static final String PRIVILEGES_SCRIPT = "application role privileges";

  private String url;

  @BeforeEach
  void newDatabase() {
    url = RoleSeparatedPostgres.newDatabase();
  }

  @Test
  void withoutAnApplicationRoleTheMigrationsGrantNothing() throws SQLException {
    migrate("");

    try (Connection owner = asOwner(url)) {
      assertThat(count(owner, GRANTED_OBJECTS)).isZero();
      assertThat(count(owner, "SELECT count(*) FROM pg_default_acl")).isZero();
      assertThat(count(owner, "SELECT count(*) FROM guests")).isZero();
    }
    try (Connection app = asApp(url)) {
      assertDenied(app, "SELECT count(*) FROM guests");
    }
  }

  @Test
  void anApplicationRoleConfiguredLaterIsGrantedOnTheNextMigration() throws SQLException {
    migrate("");
    migrate(APP);

    try (Connection owner = asOwner(url)) {
      assertThat(
              count(
                  owner,
                  "SELECT count(*) FROM flyway_schema_history WHERE description = '"
                      + PRIVILEGES_SCRIPT
                      + "' AND success"))
          .isEqualTo(2);
      assertThat(
              bool(
                  owner,
                  "SELECT has_table_privilege('"
                      + APP
                      + "', 'guests', 'SELECT, INSERT, UPDATE, DELETE')"))
          .isTrue();
    }
  }

  @Test
  void theApplicationRoleReadsAndWritesTheBusinessTables() throws SQLException {
    migrate(APP);

    try (Connection app = asApp(url)) {
      long user = insertUser(app);
      long guest = insertGuest(app);
      exec(app, "UPDATE guests SET full_name = 'Nombre de prueba' WHERE id = " + guest);
      assertThat(count(app, "SELECT count(*) FROM guests WHERE full_name = 'Nombre de prueba'"))
          .isOne();
      exec(app, "UPDATE users SET guest_id = " + guest + " WHERE id = " + user);
      exec(app, "UPDATE users SET guest_id = NULL WHERE id = " + user);
      exec(app, "DELETE FROM users WHERE id = " + user);
      assertThat(count(app, "SELECT count(*) FROM users")).isZero();
      assertThat(currentUser(app)).isEqualTo(APP);
    }
  }

  @Test
  void theApplicationRoleOnlyInsertsAndReadsTheEvidence() throws SQLException {
    migrate(APP);

    try (Connection app = asApp(url)) {
      long user = insertUser(app);
      long guest = insertGuest(app);
      long action = nextval(app, "guest_document_actions_seq");
      exec(
          app,
          "INSERT INTO guest_document_actions (id, guest_id, document_id, action, reason,"
              + " content_type, size_bytes, created_by_id) VALUES ("
              + action
              + ", "
              + guest
              + ", 7, 'DELETE', 'UPLOAD_ERROR', 'application/pdf', 10, "
              + user
              + ")");
      assertThat(count(app, "SELECT count(*) FROM guest_document_actions WHERE id = " + action))
          .isOne();

      assertDenied(app, "UPDATE guest_document_actions SET note = 'otra' WHERE id = " + action);
      assertDenied(app, "DELETE FROM guest_document_actions WHERE id = " + action);
      assertDenied(app, "TRUNCATE TABLE guest_document_actions");
      assertDenied(
          app, "DROP TRIGGER trg_guest_document_actions_immutable ON guest_document_actions");
      assertDenied(app, "ALTER TABLE guest_document_actions DISABLE TRIGGER ALL");
      assertDenied(app, "DROP TABLE guest_document_actions");
    }
    try (Connection owner = asOwner(url)) {
      assertThat(count(owner, "SELECT count(*) FROM guest_document_actions")).isOne();
      assertThat(
              count(
                  owner,
                  "SELECT count(*) FROM pg_trigger WHERE tgname ="
                      + " 'trg_guest_document_actions_immutable' AND tgenabled = 'O'"))
          .isOne();
    }
  }

  @Test
  void theApplicationRoleCannotTouchTheMigrationHistoryNorCreateObjects() throws SQLException {
    migrate(APP);

    try (Connection app = asApp(url)) {
      assertDenied(app, "SELECT count(*) FROM flyway_schema_history");
      assertDenied(app, "DELETE FROM flyway_schema_history");
      assertDenied(app, "CREATE TABLE intruder (id int)");
      assertDenied(app, "TRUNCATE TABLE guests");
    }
  }

  @Test
  void tablesTheOwnerCreatesLaterAreGrantedToo() throws SQLException {
    migrate(APP);

    try (Connection owner = asOwner(url)) {
      exec(owner, "CREATE SEQUENCE later_seq");
      exec(owner, "CREATE TABLE later_table (id BIGINT PRIMARY KEY)");
    }
    try (Connection app = asApp(url)) {
      exec(app, "INSERT INTO later_table (id) VALUES (nextval('later_seq'))");
      exec(app, "DELETE FROM later_table");
      assertThat(count(app, "SELECT count(*) FROM later_table")).isZero();
      assertDenied(app, "TRUNCATE TABLE later_table");
    }
  }

  @Test
  void nothingIsGrantedToPublic() throws SQLException {
    migrate(APP);

    try (Connection owner = asOwner(url)) {
      assertThat(count(owner, GRANTED_OBJECTS)).isPositive();
      assertThat(
              count(
                  owner,
                  "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace,"
                      + " aclexplode(c.relacl) a WHERE n.nspname = 'public' AND a.grantee = 0"))
          .isZero();
      assertThat(
              count(
                  owner,
                  "SELECT count(*) FROM pg_default_acl d, aclexplode(d.defaclacl) a"
                      + " WHERE a.grantee = 0"))
          .isZero();
      assertThat(
              count(
                  owner,
                  "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace,"
                      + " aclexplode(c.relacl) a JOIN pg_roles r ON r.oid = a.grantee"
                      + " WHERE n.nspname = 'public' AND r.rolname NOT IN ('"
                      + OWNER
                      + "', '"
                      + APP
                      + "')"))
          .isZero();
    }
  }

  @Test
  void aMissingApplicationRoleStopsTheMigration() throws SQLException {
    assertThatThrownBy(() -> migrate("quipu_ghost"))
        .isInstanceOf(FlywayException.class)
        .hasMessageContaining("El rol de aplicación quipu_ghost no existe");
    assertNothingGranted();
  }

  @ParameterizedTest
  @ValueSource(strings = {OWNER, HEIR})
  void theOwnerOrAnHeirOfItIsRefusedAsTheApplicationRole(String role) throws SQLException {
    assertThatThrownBy(() -> migrate(role))
        .isInstanceOf(FlywayException.class)
        .hasMessageContaining(
            "El rol de aplicación no puede ser superusuario, el dueño de las tablas ni heredarlo");
    assertNothingGranted();
  }

  @Test
  void aSuperuserIsRefusedAsTheApplicationRole() throws SQLException {
    assertThatThrownBy(() -> migrate(RoleSeparatedPostgres.superuser()))
        .isInstanceOf(FlywayException.class)
        .hasMessageContaining(
            "El rol de aplicación no puede ser superusuario, el dueño de las tablas ni heredarlo");
    assertNothingGranted();
  }

  @Test
  void aRoleThatCouldStillAlterTheEvidenceIsRefused() throws SQLException {
    assertThatThrownBy(() -> migrate(WRITER))
        .isInstanceOf(FlywayException.class)
        .hasMessageContaining(
            "El rol de aplicación conserva UPDATE, DELETE o TRUNCATE sobre guest_document_actions");
    assertNothingGranted();
  }

  /**
   * Objects of the schema with any explicit privilege (relacl is NULL while only the owner has).
   */
  private static final String GRANTED_OBJECTS =
      "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
          + " WHERE n.nspname = 'public' AND c.relacl IS NOT NULL";

  private void migrate(String appRole) {
    Flyway.configure()
        .dataSource(url, OWNER, OWNER_PASSWORD)
        .locations("classpath:db/migration")
        .placeholders(Map.of("app_role", appRole))
        .load()
        .migrate();
  }

  private void assertNothingGranted() throws SQLException {
    try (Connection owner = asOwner(url)) {
      assertThat(count(owner, GRANTED_OBJECTS)).isZero();
      assertThat(count(owner, "SELECT count(*) FROM pg_default_acl")).isZero();
    }
  }

  private static long insertUser(Connection connection) throws SQLException {
    long id = nextval(connection, "users_seq");
    exec(
        connection,
        "INSERT INTO users (id, document_type, document_number, password_hash, role)"
            + " VALUES ("
            + id
            + ", 'DNI', '00000001', 'hash', 'ADMIN')");
    return id;
  }

  private static long insertGuest(Connection connection) throws SQLException {
    long id = nextval(connection, "guests_seq");
    exec(
        connection,
        "INSERT INTO guests (id, document_type, document_number, guest_type, status)"
            + " VALUES ("
            + id
            + ", 'DNI', '12345678', 'CONTRACT', 'ACTIVE')");
    return id;
  }

  private static long nextval(Connection connection, String sequence) throws SQLException {
    return count(connection, "SELECT nextval('" + sequence + "')");
  }

  private static String currentUser(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("SELECT current_user")) {
      rows.next();
      return rows.getString(1);
    }
  }

  private static void exec(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static long count(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getLong(1);
    }
  }

  private static boolean bool(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getBoolean(1);
    }
  }

  private static void assertDenied(Connection connection, String sql) {
    assertThatThrownBy(() -> exec(connection, sql))
        .as(sql)
        .isInstanceOfSatisfying(
            SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE));
  }
}
