package com.tarimatwasi.quipu.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Shared PostgreSQL for integration tests (BE-SPR-TST-03); import with @ImportTestcontainers. */
public interface PostgresContainers {

  @Container @ServiceConnection
  // Every distinct Spring context the suite caches keeps its own connection pool against this one
  // database; the default 100 connections ran out with about ten contexts (TAR-124).
  PostgreSQLContainer POSTGRES = new SharedPostgres();

  /**
   * Spring Boot stops an imported container when its context closes. This one is shared by every
   * cached context, so the first to close stopped it under the others: each then waited for a
   * connection to close its outbox until Surefire killed the JVM. It outlives them all instead, and
   * Ryuk removes it when the JVM ends.
   */
  final class SharedPostgres extends PostgreSQLContainer {
    SharedPostgres() {
      super("postgres:18-alpine");
      withCommand("postgres", "-c", "max_connections=300");
    }

    @Override
    public void stop() {
      // Removed by Ryuk with the JVM, after every context has closed.
    }
  }
}
