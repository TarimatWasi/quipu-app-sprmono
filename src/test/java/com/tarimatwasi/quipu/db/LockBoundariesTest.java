package com.tarimatwasi.quipu.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tarimatwasi.quipu.guest.port.in.LockGuestUseCase;
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
   * A migration waits for a lock as long as it takes (spring.flyway.init-sqls: a deploy is not cut
   * by the limit of the requests), and the connections it borrowed do not go back to the pool
   * without the limit. In dev and prod Flyway migrates over the pool of the application; here the
   * service connection gives it its own, so the probe is pointed at the pool to migrate as there.
   */
  @Test
  void migrationsRunWithoutTheLockTimeoutAndLeaveThePoolWithIt() throws SQLException {
    List<String> seen = new CopyOnWriteArrayList<>();
    String schema = "lock_probe_" + TestIds.next();
    Flyway probe =
        Flyway.configure()
            .configuration(flyway.getConfiguration())
            .dataSource(pool)
            .schemas(schema)
            .createSchemas(true)
            .callbacks(new LockTimeoutProbe(seen))
            .load();

    try {
      strategy.migrate(probe);
    } finally {
      // Other tests read the catalog: the copy of the tables does not stay.
      jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    assertThat(seen).isNotEmpty().containsOnly("0");
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
  private record LockTimeoutProbe(List<String> seen) implements Callback {

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
    }

    @Override
    public String getCallbackName() {
      return "lock-timeout-probe";
    }
  }
}
