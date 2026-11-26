package com.tarimatwasi.quipu.auth.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tarimatwasi.quipu.auth.application.port.out.AccountAlreadyExistsException;
import com.tarimatwasi.quipu.auth.application.port.out.InitialAdminStorePort.NewAdmin;
import com.tarimatwasi.quipu.auth.domain.DocumentType;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.TestTables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@ImportTestcontainers(PostgresContainers.class)
class InitialAdminStoreAdapterTest {

  @Autowired InitialAdminStoreAdapter adapter;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
  }

  /** Two startups that create the initial ADMIN at once: the second finds it there. */
  @Test
  void theSameDocumentTwiceIsAnAccountThatAlreadyExists() {
    adapter.insert(new NewAdmin("admin@quipu.test", DocumentType.DNI, "00000001", "hash"));

    assertThat(adapter.countActiveAdmins()).isEqualTo(1);
    assertThatThrownBy(
            () ->
                adapter.insert(
                    new NewAdmin("other@quipu.test", DocumentType.DNI, "00000001", "hash")))
        .isInstanceOf(AccountAlreadyExistsException.class);
  }
}
