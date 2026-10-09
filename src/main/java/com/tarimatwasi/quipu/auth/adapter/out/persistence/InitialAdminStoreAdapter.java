package com.tarimatwasi.quipu.auth.adapter.out.persistence;

import com.tarimatwasi.quipu.auth.port.out.AccountAlreadyExistsException;
import com.tarimatwasi.quipu.auth.port.out.InitialAdminStorePort;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class InitialAdminStoreAdapter implements InitialAdminStorePort {

  private final JdbcTemplate jdbc;

  public InitialAdminStoreAdapter(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public int countActiveAdmins() {
    // same rule as UserAccount.isDisabled(): an INACTIVE ADMIN cannot log in
    Integer admins =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM users WHERE role = 'ADMIN' AND status <> 'INACTIVE'",
            Integer.class);
    return admins == null ? 0 : admins;
  }

  @Override
  public void insert(NewAdmin admin) {
    try {
      jdbc.update(
          // Users are only ever created here, by JDBC, so users_seq is not shared with Hibernate's
          // block allocation. If a user is ever persisted through JPA, give this insert its own id
          // source: both would draw from the same sequence.
          "INSERT INTO users (id, email, document_type, document_number, password_hash, role, "
              + "must_change_password, status) "
              + "VALUES (nextval('users_seq'), ?, ?, ?, ?, 'ADMIN', TRUE, 'ACTIVE')",
          admin.email(),
          admin.documentType().name(),
          admin.documentNumber(),
          admin.passwordHash());
    } catch (DuplicateKeyException e) {
      throw new AccountAlreadyExistsException(e);
    }
  }
}
