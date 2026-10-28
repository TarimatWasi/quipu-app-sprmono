package com.tarimatwasi.quipu.guest.infrastructure.adapter.out.persistence;

import org.hibernate.exception.ConstraintViolationException;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * TAR-207: whether a write broke a given constraint, read from the name the server reports, so the
 * language of its messages does not matter. Hibernate's own name is only a fallback: it parses it
 * out of the English wording.
 */
final class ViolatedConstraint {

  private ViolatedConstraint() {}

  static boolean is(Throwable failure, String constraint) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof PSQLException psql) {
        ServerErrorMessage server = psql.getServerErrorMessage();
        if (server != null && server.getConstraint() != null) {
          return constraint.equals(server.getConstraint());
        }
      }
    }
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof ConstraintViolationException violation) {
        return constraint.equals(violation.getConstraintName());
      }
    }
    return false;
  }
}
