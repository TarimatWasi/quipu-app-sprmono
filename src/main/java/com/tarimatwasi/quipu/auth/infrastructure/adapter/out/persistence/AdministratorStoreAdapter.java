package com.tarimatwasi.quipu.auth.infrastructure.adapter.out.persistence;

import com.tarimatwasi.quipu.auth.application.port.out.AccountTakenException;
import com.tarimatwasi.quipu.auth.application.port.out.AccountTakenException.Taken;
import com.tarimatwasi.quipu.auth.application.port.out.AdministratorStorePort;
import com.tarimatwasi.quipu.auth.domain.DocumentType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
   * The key of the transaction-level advisory lock that serializes the creation (RF-22),
   * deactivation and reactivation (TAR-127) of administrators. Any fixed number works as long as
   * nothing else uses it: these are the bytes of "QUIPUADM".
   */
  static final long ADMINISTRATORS_LOCK = 0x515549505541444DL;

  /** uq_users_document: the document of any account, administrator or guest. */
  static final String DOCUMENT_CONSTRAINT = "uq_users_document";

  /** users_email_key and the case-insensitive index uq_users_email_lower. */
  static final Set<String> EMAIL_CONSTRAINTS = Set.of("users_email_key", "uq_users_email_lower");

  /** What a {@link StoredAdministrator} is read from. */
  private static final String COLUMNS =
      "id, document_type, document_number, email, status, created_date";

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

  @Override
  public List<StoredAdministrator> listAdministrators() {
    // At most the maximum of administrators (5 by default): no page is needed.
    return jdbc.query(
        "SELECT " + COLUMNS + " FROM users WHERE role = 'ADMIN' ORDER BY created_date, id",
        AdministratorStoreAdapter::administrator);
  }

  /**
   * FOR NO KEY UPDATE, the lock that the UPDATE of the status takes anyway: it does not block the
   * FOR KEY SHARE that the foreign key last_modified_by_id takes on the row of an actor ({@link
   * #holdAsReference}), so two administrators who change each other wait only for the lock of the
   * administrators, never in a circle.
   */
  @Override
  public Optional<StoredAdministrator> lockAdministrator(Long id) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("The lock of an administrator needs a transaction");
    }
    return jdbc
        .query(
            "SELECT " + COLUMNS + " FROM users WHERE id = ? AND role = 'ADMIN' FOR NO KEY UPDATE",
            AdministratorStoreAdapter::administrator,
            id)
        .stream()
        .findFirst();
  }

  /** FOR KEY SHARE, the lock that the foreign key last_modified_by_id takes on the row. */
  @Override
  public void holdAsReference(Long id) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Holding a row needs a transaction");
    }
    jdbc.queryForList("SELECT id FROM users WHERE id = ? FOR KEY SHARE", id);
  }

  @Override
  public int countActiveAdministrators() {
    // The same rule as UserAccount.isDisabled() and the initial ADMIN: only INACTIVE is out.
    Integer active =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM users WHERE role = 'ADMIN' AND status <> 'INACTIVE'",
            Integer.class);
    return active == null ? 0 : active;
  }

  @Override
  public void setAdministratorStatus(Long id, String status, Long modifiedBy) {
    // The audit columns, as the JPA auditing fills them: when and who changed the row.
    int updated =
        jdbc.update(
            "UPDATE users SET status = ?, last_modified_date = now(), last_modified_by_id = ?"
                + " WHERE id = ? AND role = 'ADMIN'",
            status,
            modifiedBy,
            id);
    if (updated != 1) {
      throw new IllegalStateException("No administrator with id " + id);
    }
  }

  private static StoredAdministrator administrator(ResultSet row, int n) throws SQLException {
    return new StoredAdministrator(
        row.getLong("id"),
        DocumentType.valueOf(row.getString("document_type")),
        row.getString("document_number"),
        row.getString("email"),
        row.getString("status"),
        Objects.requireNonNull(row.getObject("created_date", OffsetDateTime.class)).toInstant());
  }

  private record Admin(Long id, String email) {}
}
