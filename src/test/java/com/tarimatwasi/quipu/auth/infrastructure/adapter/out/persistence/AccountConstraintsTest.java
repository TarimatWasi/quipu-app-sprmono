package com.tarimatwasi.quipu.auth.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.auth.application.port.out.AccountTakenException;
import com.tarimatwasi.quipu.auth.application.port.out.AccountTakenException.Taken;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;

class AccountConstraintsTest {

  @Test
  void theDocumentAndBothRulesOfTheEmailTellWhatIsTaken() {
    assertThat(AccountConstraints.taken(broke("uq_users_document"))).isEqualTo(Taken.DOCUMENT);
    assertThat(AccountConstraints.taken(broke("users_email_key"))).isEqualTo(Taken.EMAIL);
    assertThat(AccountConstraints.taken(broke("uq_users_email_lower"))).isEqualTo(Taken.EMAIL);
  }

  /** Another rule, or none at all, takes nothing: the failure is kept as it is. */
  @Test
  void anyOtherRuleOrNoneTakesNothing() {
    assertThat(AccountConstraints.taken(broke("users_pkey"))).isNull();
    assertThat(AccountConstraints.taken(new SQLException("down"))).isNull();
  }

  private static PSQLException broke(String constraint) {
    return new PSQLException(new ServerErrorMessage("SERROR\0C23505\0n" + constraint + "\0"));
  }

  /** A rule of the accounts becomes what was taken; any other failure stays as it was. */
  @Test
  void aFailureIsTranslatedOnlyWhenItBrokeARuleOfTheAccounts() {
    var email = new DataIntegrityViolationException("x", broke("users_email_key"));
    var other = new DataIntegrityViolationException("x", broke("users_pkey"));

    assertThat(AccountConstraints.translated(email))
        .isInstanceOfSatisfying(
            AccountTakenException.class, e -> assertThat(e.taken()).isEqualTo(Taken.EMAIL))
        .hasCause(email);
    assertThat(AccountConstraints.translated(other)).isSameAs(other);
  }
}
