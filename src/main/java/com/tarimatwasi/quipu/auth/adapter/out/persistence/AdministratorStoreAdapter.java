package com.tarimatwasi.quipu.auth.adapter.out.persistence;

import com.tarimatwasi.quipu.auth.port.out.AccountTakenException;
import com.tarimatwasi.quipu.auth.port.out.AccountTakenException.Taken;
import com.tarimatwasi.quipu.auth.port.out.AdministratorStorePort;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * By JDBC, like the initial ADMIN, so users_seq is never shared with Hibernate's block allocation
 * (see {@link InitialAdminStoreAdapter}).
 */
@Component
public class AdministratorStoreAdapter implements AdministratorStorePort {

  /**
   * The key of the transaction-level advisory lock that serializes the creation of administrators
   * (RF-22). Any fixed number works as long as nothing else uses it: these are the bytes of
   * "QUIPUADM".
   */
  static final long ADMINISTRATORS_LOCK = 0x515549505541444DL;

  /** uq_users_document (V1): the document of any account, administrator or guest. */
  static final String DOCUMENT_CONSTRAINT = "uq_users_document";

  /** The UNIQUE of V1 and the case-insensitive index of V2. */
  static final Set<String> EMAIL_CONSTRAINTS = Set.of("users_email_key", "uq_users_email_lower");

  private final JdbcTemplate jdbc;

  public AdministratorStoreAdapter(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void lockAdministrators() {
    // Outside a transaction the lock would end with the statement and serialize nothing.
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("The lock of the administrators needs a transaction");
    }
    jdbc.queryForList("SELECT pg_advisory_xact_lock(?)", ADMINISTRATORS_LOCK);
  }

  @Override
  public int countAdministrators() {
    Integer admins =
        jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE role = 'ADMIN'", Integer.class);
    return admins == null ? 0 : admins;
  }

  @Override
  public Long insert(NewAdministrator administrator) {
    try {
      return Objects.requireNonNull(
          jdbc.queryForObject(
              "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
                  + " must_change_password, status, created_by_id)"
                  + " VALUES (nextval('users_seq'), ?, ?, ?, ?, 'ADMIN', TRUE, 'ACTIVE', ?)"
                  + " RETURNING id",
              Long.class,
              administrator.email(),
              administrator.documentType().name(),
              administrator.documentNumber(),
              administrator.passwordHash(),
              administrator.createdBy()));
    } catch (DuplicateKeyException e) {
      // By the name of the rule, never by the message: the language of the server does not matter.
      String constraint = ConstraintName.of(e);
      if (DOCUMENT_CONSTRAINT.equals(constraint)) {
        throw new AccountTakenException(Taken.DOCUMENT, e);
      }
      if (constraint != null && EMAIL_CONSTRAINTS.contains(constraint)) {
        throw new AccountTakenException(Taken.EMAIL, e);
      }
      throw e;
    }
  }

  @Override
  public List<String> activeAdministratorEmailsExcept(Collection<Long> excluded) {
    // same rule as UserAccount.isDisabled(): an INACTIVE ADMIN cannot log in. There are a handful
    // of administrators at most, so the excluded ones are left out here.
    return jdbc
        .query(
            "SELECT id, email FROM users WHERE role = 'ADMIN' AND status <> 'INACTIVE' ORDER BY id",
            (row, n) -> new Admin(row.getLong("id"), row.getString("email")))
        .stream()
        .filter(admin -> !excluded.contains(admin.id()))
        .map(Admin::email)
        .toList();
  }

  private record Admin(Long id, String email) {}
}
