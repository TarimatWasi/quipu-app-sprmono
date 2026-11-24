package com.tarimatwasi.quipu.shared.adapter.out.persistence;

import org.hibernate.exception.ConstraintViolationException;
import org.jspecify.annotations.Nullable;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * TAR-207: the constraint a write broke, read from the name the server reports, so the language of
 * its messages does not matter (a Spanish server words them differently, and the driver leaves the
 * detail out: logServerErrorDetail). Hibernate's own name is only a fallback: it parses it out of
 * the English wording. The only reader of that name (QP-SPRMONO-DAT-06): three modules had a copy,
 * and one read the message.
 */
public final class ViolatedConstraint {

  private ViolatedConstraint() {}

  /** The name of the constraint the failure, or what it wraps, broke; null if none. */
  public static @Nullable String nameOf(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof PSQLException psql) {
        ServerErrorMessage server = psql.getServerErrorMessage();
        if (server != null && server.getConstraint() != null) {
          return server.getConstraint();
        }
      }
    }
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof ConstraintViolationException violation) {
        return violation.getConstraintName();
      }
    }
    return null;
  }

  /** Whether the failure broke exactly that constraint. */
  public static boolean is(Throwable failure, String constraint) {
    return constraint.equals(nameOf(failure));
  }
}
