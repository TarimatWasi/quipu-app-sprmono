package com.tarimatwasi.quipu.shared.config;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import javax.sql.DataSource;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * TAR-204: the migrations wait for a lock up to 60 s ({@code spring.flyway.init-sqls}), longer than
 * the 15 s of the requests, so a deploy is not cut, and an ACCESS EXCLUSIVE behind an idle
 * transaction does not hold the table forever. Flyway borrows connections from the pool and that
 * setting stays on them, so once it is done, or has failed, they are evicted: the next ones open
 * with the {@code connection-init-sql} of the application again.
 */
@Configuration(proxyBeanMethods = false)
class FlywayConfig {

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
}
