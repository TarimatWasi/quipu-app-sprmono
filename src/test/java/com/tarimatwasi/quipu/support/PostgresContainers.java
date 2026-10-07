package com.tarimatwasi.quipu.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Shared PostgreSQL for integration tests (BE-SPR-TST-03); import with @ImportTestcontainers. */
public interface PostgresContainers {

  @Container @ServiceConnection
  // Every distinct Spring context the suite caches keeps its own connection pool against this one
  // database; the default 100 connections ran out with about ten contexts (TAR-124).
  PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine")
          .withCommand("postgres", "-c", "max_connections=300");
}
