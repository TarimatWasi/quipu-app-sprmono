package com.tarimatwasi.quipu.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * TAR-214: a PostgreSQL of its own with the roles of a deployment that separates them, as in Neon:
 * an owner role without superuser that owns the database and runs the migrations, and an
 * application role that only logs in. The passwords are random per run; nothing here is a secret.
 * Each call to {@link #newDatabase()} gives an empty database owned by the owner role, so the tests
 * do not share grants or migration history.
 */
public final class RoleSeparatedPostgres {

  public static final String OWNER = "quipu_owner";
  public static final String APP = "quipu_app";

  /** Role created with LOGIN only, to try configurations the migration must refuse. */
  public static final String HEIR = "quipu_heir";

  /** Member of pg_write_all_data: could UPDATE and DELETE any table despite the migration. */
  public static final String WRITER = "quipu_writer";

  public static final String OWNER_PASSWORD = UUID.randomUUID().toString();
  public static final String APP_PASSWORD = UUID.randomUUID().toString();

  private static final AtomicInteger DATABASES = new AtomicInteger();

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  static {
    POSTGRES.start();
    asSuperuser(
        "CREATE ROLE " + OWNER + " LOGIN PASSWORD '" + OWNER_PASSWORD + "'",
        "CREATE ROLE " + APP + " LOGIN PASSWORD '" + APP_PASSWORD + "'",
        "CREATE ROLE " + HEIR + " LOGIN",
        "GRANT " + OWNER + " TO " + HEIR,
        "CREATE ROLE " + WRITER + " LOGIN",
        "GRANT pg_write_all_data TO " + WRITER);
  }

  private RoleSeparatedPostgres() {}

  /** The superuser of the container, which the migration must refuse as the application role. */
  public static String superuser() {
    return POSTGRES.getUsername();
  }

  /** Creates an empty database owned by the owner role and returns its JDBC URL. */
  public static String newDatabase() {
    String name = "quipu_roles_" + DATABASES.incrementAndGet();
    asSuperuser("CREATE DATABASE " + name + " OWNER " + OWNER);
    return POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + name);
  }

  public static Connection asOwner(String url) throws SQLException {
    return DriverManager.getConnection(url, OWNER, OWNER_PASSWORD);
  }

  public static Connection asApp(String url) throws SQLException {
    return DriverManager.getConnection(url, APP, APP_PASSWORD);
  }

  private static void asSuperuser(String... statements) {
    try (Connection connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement statement = connection.createStatement()) {
      for (String sql : statements) {
        statement.execute(sql);
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Could not prepare the role-separated database", e);
    }
  }
}
