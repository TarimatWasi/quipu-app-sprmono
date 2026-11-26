package com.tarimatwasi.quipu.shared.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;

/** SpanishServerConstraintsTest checks it against a real server that answers in Spanish. */
class ViolatedConstraintTest {

  @Test
  void theNameTheServerReportsWins() {
    var server = new PSQLException(new ServerErrorMessage("SERROR\0C23505\0nuq_users_email\0"));
    var failure =
        new DataIntegrityViolationException(
            "x", new ConstraintViolationException("x", server, "parsed_by_hibernate"));

    assertThat(ViolatedConstraint.nameOf(failure)).isEqualTo("uq_users_email");
    assertThat(ViolatedConstraint.is(failure, "uq_users_email")).isTrue();
    assertThat(ViolatedConstraint.is(failure, "parsed_by_hibernate")).isFalse();
  }

  /** Without the name of the server (no message, or one without it), the one Hibernate parsed. */
  @Test
  void withoutTheNameOfTheServer_theOneOfHibernate() {
    var noMessage = new PSQLException("x", PSQLState.UNIQUE_VIOLATION);
    var noConstraint = new PSQLException(new ServerErrorMessage("SERROR\0C23505\0"));

    assertThat(
            ViolatedConstraint.nameOf(
                new ConstraintViolationException("x", noMessage, "fk_fines_guest")))
        .isEqualTo("fk_fines_guest");
    assertThat(
            ViolatedConstraint.nameOf(
                new ConstraintViolationException("x", noConstraint, "fk_fines_guest")))
        .isEqualTo("fk_fines_guest");
  }

  @Test
  void aFailureThatBrokeNoConstraint_hasNone() {
    assertThat(ViolatedConstraint.nameOf(new SQLException("down"))).isNull();
    assertThat(ViolatedConstraint.is(new SQLException("down"), "uq_users_email")).isFalse();
  }
}
