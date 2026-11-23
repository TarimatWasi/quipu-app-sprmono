package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/**
 * v0.29.0 (TAR-204): the database could not serve the request in time, so nothing of it was saved
 * and it can be repeated: 503 {@code SERVICE_BUSY} with a Retry-After. Either no connection came
 * free within the wait of the pool (the transaction never started), or Postgres cancelled a
 * statement past statement_timeout (57014) and the transaction rolled back.
 *
 * <p>It is recognized by its cause, wrapped as it may be: JDBC, the repositories of JPA, the start
 * of a transaction and the security filters each wrap it differently (BusyDatabaseTest). A lock
 * wait past lock_timeout (55P03) is not this: it stays CONCURRENT_MODIFICATION.
 */
public final class ServiceBusy {

  public static final String CODE = "SERVICE_BUSY";

  /** About the wait of the pool for a connection. */
  public static final String RETRY_AFTER_SECONDS = "5";

  /** Postgres: query_canceled, which statement_timeout raises. */
  private static final String STATEMENT_CANCELLED = "57014";

  /** Wrapped deeper than this, it is not looked for. */
  private static final int MAX_DEPTH = 16;

  private static final String BODY =
      "{\"code\":\""
          + CODE
          + "\",\"message\":\"El sistema está ocupado y no guardó nada; inténtalo de nuevo en unos"
          + " segundos\"}";

  private ServiceBusy() {}

  /** Whether the failure, or what it wraps, is the database not serving in time. */
  public static boolean causedBy(@Nullable Throwable failure) {
    Throwable current = failure;
    for (int depth = 0; current != null && depth < MAX_DEPTH; depth++) {
      if (current instanceof SQLTransientConnectionException
          || current instanceof QueryTimeoutException
          || (current instanceof SQLException sql
              && STATEMENT_CANCELLED.equals(sql.getSQLState()))) {
        return true;
      }
      current = current.getCause(); // a cycle ends at MAX_DEPTH
    }
    return false;
  }

  /** Writes the 503 by hand: in a filter no controller advice runs. */
  public static void write(HttpServletResponse response) throws IOException {
    response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
    response.setHeader(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(BODY);
  }
}
