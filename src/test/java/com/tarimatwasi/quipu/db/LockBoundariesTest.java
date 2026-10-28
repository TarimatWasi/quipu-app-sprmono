package com.tarimatwasi.quipu.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tarimatwasi.quipu.guest.application.port.in.LockGuestUseCase;
import com.tarimatwasi.quipu.shared.adapter.out.storage.R2Properties;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.TestIds;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.IllegalTransactionStateException;

/** TAR-204: a row lock lives only inside a transaction and never waits without an end. */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@ImportTestcontainers(PostgresContainers.class)
class LockBoundariesTest {

  private static final String APPLICATION_LOCK_TIMEOUT = "15s";

  /** spring.flyway.init-sqls sets 60s; Postgres shows it as 1min. */
  private static final String MIGRATION_LOCK_TIMEOUT = "1min";

  @Autowired LockGuestUseCase locks;
  @Autowired JdbcTemplate jdbc;
  @Autowired HikariDataSource pool;
  @Autowired Flyway flyway;
  @Autowired FlywayMigrationStrategy strategy;
  @Autowired R2Properties r2;

  @AfterEach
  void clearSession() {
    SecurityContextHolder.clearContext();
  }

  /** Outside a transaction the lock of the guest would end at once: it is refused. */
  @Test
  void lockingAGuestOutsideATransactionIsRefused() {
    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken("1", "x", "ROLE_ADMIN"));

    assertThatThrownBy(() -> locks.lock(TestIds.next()))
        .isInstanceOf(IllegalTransactionStateException.class);
  }

  /** Every connection of the application waits for a row lock 15 s at most. */
  @Test
  void theConnectionsOfTheApplicationHaveALockTimeout() {
    assertThat(jdbc.queryForObject("SHOW lock_timeout", String.class))
        .isEqualTo(APPLICATION_LOCK_TIMEOUT);
  }

  /** A call to R2 made while rows are locked gives up before the lock of anyone waiting does. */
  @Test
  void theTimeoutOfR2IsShorterThanTheLockTimeout() {
    String lockTimeout = jdbc.queryForObject("SHOW lock_timeout", String.class);
    assertThat(lockTimeout).endsWith("s");
    Duration lock = Duration.ofSeconds(Long.parseLong(lockTimeout.replace("s", "")));

    assertThat(r2.apiCallTimeout()).isLessThan(lock).isGreaterThan(r2.apiCallAttemptTimeout());
  }

  /**
   * A migration waits for a lock longer than a request (spring.flyway.init-sqls: 60 s, so a deploy
   * is not cut by the limit of the requests, yet an ACCESS EXCLUSIVE behind an idle transaction
   * does not hold the table forever), and the connections it borrowed do not go back to the pool
   * with that limit. In dev and prod Flyway migrates over the pool of the application; here the
   * service connection gives it its own, so the probe is pointed at the pool to migrate as there.
   */
  @Test
  void migrationsRunWithTheirOwnLockTimeoutAndLeaveThePoolWithTheApplicationOne()
      throws SQLException {
    List<String> seen = new CopyOnWriteArrayList<>();

    migrateAProbe(new LockTimeoutProbe(seen, false));

    assertThat(seen).isNotEmpty().containsOnly(MIGRATION_LOCK_TIMEOUT);
    assertThatEveryPooledConnectionHasTheApplicationLockTimeout();
  }

  /** A migration that fails does not leave the pool with the limit of the migrations either. */
  @Test
  void aMigrationThatFailsLeavesThePoolWithTheApplicationLockTimeout() throws SQLException {
    List<String> seen = new CopyOnWriteArrayList<>();

    assertThatThrownBy(() -> migrateAProbe(new LockTimeoutProbe(seen, true)))
        .isInstanceOf(RuntimeException.class);

    assertThat(seen).containsOnly(MIGRATION_LOCK_TIMEOUT);
    assertThatEveryPooledConnectionHasTheApplicationLockTimeout();
  }

  private void migrateAProbe(Callback callback) {
    String schema = "lock_probe_" + TestIds.next();
    Flyway probe =
        Flyway.configure()
            .configuration(flyway.getConfiguration())
            .dataSource(pool)
            .schemas(schema)
            .createSchemas(true)
            .callbacks(callback)
            .load();
    try {
      strategy.migrate(probe);
    } finally {
      // Other tests read the catalog: the copy of the tables does not stay.
      jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  private void assertThatEveryPooledConnectionHasTheApplicationLockTimeout() throws SQLException {
    List<Connection> all = new ArrayList<>();
    try {
      for (int i = 0; i < pool.getMaximumPoolSize(); i++) {
        all.add(pool.getConnection());
      }
      for (Connection connection : all) {
        assertThat(lockTimeoutOf(connection)).isEqualTo(APPLICATION_LOCK_TIMEOUT);
      }
    } finally {
      for (Connection connection : all) {
        connection.close();
      }
    }
  }

  private static String lockTimeoutOf(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
        var result = statement.executeQuery("SHOW lock_timeout")) {
      result.next();
      return result.getString(1);
    }
  }

  /** Reads lock_timeout on the connection Flyway migrates with. */
  private record LockTimeoutProbe(List<String> seen, boolean failAfterReading) implements Callback {

    @Override
    public boolean supports(Event event, Context context) {
      return event == Event.BEFORE_EACH_MIGRATE || event == Event.AFTER_MIGRATE;
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
      return true;
    }

    @Override
    public void handle(Event event, Context context) {
      try {
        seen.add(lockTimeoutOf(context.getConnection()));
      } catch (SQLException e) {
        throw new IllegalStateException(e);
      }
      if (failAfterReading) {
        throw new IllegalStateException("the migration fails on purpose");
      }
    }

    @Override
    public String getCallbackName() {
      return "lock-timeout-probe";
    }
  }
}
