package com.tarimatwasi.quipu.auth.adapter.out.persistence;

import org.jspecify.annotations.Nullable;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * TAR-207: the constraint a write broke, read from the name the server reports, so the language of
 * its messages does not matter (as {@code ViolatedConstraint} of the guests).
 */
final class ConstraintName {

  private ConstraintName() {}

  static @Nullable String of(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof PSQLException psql) {
        ServerErrorMessage server = psql.getServerErrorMessage();
        if (server != null && server.getConstraint() != null) {
          return server.getConstraint();
        }
      }
    }
    return null;
  }
}
