package com.tarimatwasi.quipu.shared.config;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import javax.sql.DataSource;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * TAR-204: the migrations run without the lock_timeout of the requests ({@code
 * spring.flyway.init-sqls}), so a deploy waits for a lock instead of being cut. Flyway borrows
 * connections from the pool and that setting stays on them, so once it is done they are evicted:
 * the next ones open with the {@code connection-init-sql} of the application again.
 */
@Configuration(proxyBeanMethods = false)
class FlywayConfig {

  @Bean
  FlywayMigrationStrategy migrateThenRenewConnections(DataSource dataSource) {
    return flyway -> {
      flyway.migrate();
      if (dataSource instanceof HikariDataSource hikari) {
        HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
        if (pool != null) {
          pool.softEvictConnections();
        }
      }
    };
  }
}
