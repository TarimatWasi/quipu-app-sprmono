package com.tarimatwasi.quipu.shared.config;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * TAR-204: the migrations wait for a lock up to 60 s ({@code spring.flyway.init-sqls}), longer than
 * the 15 s of the requests, so a deploy is not cut, and an ACCESS EXCLUSIVE behind an idle
 * transaction does not hold the table forever. Flyway borrows connections from the pool and that
 * setting stays on them, so once it is done, or has failed, they are evicted: the next ones open
 * with the {@code connection-init-sql} of the application again.
 *
 * <p>TAR-214: with {@code app.migration.url} Flyway migrates through a connection of its own, as
 * the owner of the tables, and the migrations grant the application role ({@link
 * MigrationProperties}).
 */
@Configuration(proxyBeanMethods = false)
class FlywayConfig {

  /** Placeholder of R__application_role_privileges.sql; empty when there is no application role. */
  static final String APP_ROLE_PLACEHOLDER = "app_role";

  @Bean
  FlywayMigrationStrategy migrateThenRenewConnections(DataSource dataSource) {
    return flyway -> {
      try {
        flyway.migrate();
      } finally {
        // Also when the migration fails: the connections it borrowed keep its lock_timeout.
        if (dataSource instanceof HikariDataSource hikari) {
          HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
          if (pool != null) {
            pool.softEvictConnections();
          }
        }
      }
    };
  }

  /**
   * Always defines the placeholder, so the privileges script resolves without a role, and points
   * Flyway at the owner connection when there is one. Customizers run after Spring Boot set the
   * data source of the application, so this one wins.
   */
  @Bean
  FlywayConfigurationCustomizer migrationConnection(MigrationProperties migration) {
    return configuration -> {
      Map<String, String> placeholders = new HashMap<>(configuration.getPlaceholders());
      placeholders.put(APP_ROLE_PLACEHOLDER, migration.appRole());
      configuration.placeholders(placeholders);
      if (migration.separated()) {
        configuration.dataSource(migration.ownerDataSource());
      }
    };
  }
}
