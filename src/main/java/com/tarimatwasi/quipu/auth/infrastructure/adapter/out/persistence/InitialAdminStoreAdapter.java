package com.tarimatwasi.quipu.auth.infrastructure.adapter.out.persistence;

import com.tarimatwasi.quipu.auth.application.port.out.AccountAlreadyExistsException;
import com.tarimatwasi.quipu.auth.application.port.out.InitialAdminStorePort;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class InitialAdminStoreAdapter implements InitialAdminStorePort {

  private final JdbcClient jdbc;

  public InitialAdminStoreAdapter(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public int countActiveAdmins() {
    // same rule as UserAccount.isDisabled(): an INACTIVE ADMIN cannot log in
    return jdbc.sql("SELECT COUNT(*) FROM users WHERE role = 'ADMIN' AND status <> 'INACTIVE'")
        .query(Integer.class)
        .single();
  }

  @Override
  public void insert(NewAdmin admin) {
    try {
      // Users are only ever created here, by JDBC, so users_seq is not shared with Hibernate's
      // block allocation. If a user is ever persisted through JPA, give this insert its own id
      // source: both would draw from the same sequence.
      jdbc.sql(
              "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
                  + " must_change_password, status) VALUES (nextval('users_seq'), :email,"
                  + " :documentType, :documentNumber, :passwordHash, 'ADMIN', TRUE, 'ACTIVE')")
          .param("email", admin.email())
          .param("documentType", admin.documentType().name())
          .param("documentNumber", admin.documentNumber())
          .param("passwordHash", admin.passwordHash())
          .update();
    } catch (DuplicateKeyException e) {
      throw new AccountAlreadyExistsException(e);
    }
  }
}
