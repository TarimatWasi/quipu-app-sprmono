package com.tarimatwasi.quipu.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.tarimatwasi.quipu.auth.infrastructure.adapter.out.security.JwtTokenProvider;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.shared.masking.MaskSession;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.SessionIds;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import jakarta.servlet.http.Cookie;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * TAR-36: deactivating a guest and its login with a temporary password at the same time. The login
 * locks only the account and the deactivation locks the guest, then the account, then the accesses,
 * so neither waits for the other in a cycle: both finish, and none answers 500.
 */
@SpringBootTest(
    properties = {
      "app.cors.allowed-origin=http://localhost:3000",
      "app.rate-limit.per-account-per-minute=1000"
    })
@AutoConfigureMockMvc
@ImportTestcontainers(PostgresContainers.class)
class GuestAccessConcurrencyTest {

  private static final int ROUNDS = 10;

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired JwtTokenProvider jwtTokenProvider;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired IdMasker masker;

  /**
   * TAR-227: every account of a test signs in to this one session, so the ids of its requests and
   * of its checks are masked alike.
   */
  private final String sessionId = SessionIds.newSessionId();

  private final Long adminId = TestIds.next();

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " must_change_password, status)"
            + " VALUES (?, 'admin@example.test', 'DNI', '00000001', 'hash', 'ADMIN', FALSE,"
            + " 'ACTIVE')",
        adminId);
  }

  @AfterEach
  void tearDown() {
    TestTables.clear(jdbc);
  }

  private Cookie admin() {
    return new Cookie("sessionToken", jwtTokenProvider.renew(adminId.toString(), sessionId));
  }

  @Test
  void aDeactivationAndATemporaryLoginAtOnceNeverFailWithAServerError() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int round = 0; round < ROUNDS; round++) {
        String number = String.format("7000%04d", round);
        String created = register(number);
        String guestId = JsonPath.read(created, "$.guest.id");
        String temporary = JsonPath.read(created, "$.access.temporaryPassword");

        CountDownLatch start = new CountDownLatch(1);
        Future<Integer> deactivation =
            pool.submit(
                () -> {
                  start.await();
                  return deactivate(guestId);
                });
        Future<Integer> login =
            pool.submit(
                () -> {
                  start.await();
                  return login(number, temporary);
                });
        start.countDown();

        assertThat(deactivation.get(20, TimeUnit.SECONDS)).isIn(204, 409);
        // Before the deactivation commits the login works; after it, the account is disabled
        // (403) or its access revoked (401).
        assertThat(login.get(20, TimeUnit.SECONDS)).isIn(200, 401, 403, 409);
      }
    } finally {
      pool.shutdownNow();
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM access_grants WHERE used_at IS NULL AND revoked_at IS NULL",
                Integer.class))
        .isZero();
  }

  /**
   * The password change reads the account only after the lock of the guest: a deactivation that
   * commits while it waits is seen, and the account stays INACTIVE whatever the order.
   */
  @Test
  void aPasswordChangeNeverRevivesAnAccountDeactivatedMeanwhile() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int round = 0; round < ROUNDS; round++) {
        Long guest = TestIds.next();
        jdbc.update(
            "INSERT INTO guests (id, document_type, document_number, guest_type, status)"
                + " VALUES (?, 'DNI', ?, 'CONTRACT', 'ACTIVE')",
            guest,
            String.format("7100%04d", round));
        Long account = TestIds.next();
        jdbc.update(
            "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
                + " guest_id, must_change_password, status) VALUES (?, NULL, 'DNI', ?, ?, 'GUEST',"
                + " ?, FALSE, 'ACTIVE')",
            account,
            String.format("7100%04d", round),
            passwordEncoder.encode("Actual-2026"),
            guest);
        Cookie session =
            new Cookie("sessionToken", jwtTokenProvider.renew(account.toString(), sessionId));
        String guestId = sessionIds().mask(IdKind.GUEST, guest).toString();

        CountDownLatch start = new CountDownLatch(1);
        Future<Integer> deactivation =
            pool.submit(
                () -> {
                  start.await();
                  return deactivate(guestId);
                });
        Future<Integer> change =
            pool.submit(
                () -> {
                  start.await();
                  return changePassword(session);
                });
        start.countDown();

        assertThat(deactivation.get(20, TimeUnit.SECONDS)).isIn(204, 409);
        assertThat(change.get(20, TimeUnit.SECONDS)).isIn(204, 401, 403, 409);
        assertThat(
                jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, account))
            .isEqualTo("INACTIVE");
      }
    } finally {
      pool.shutdownNow();
    }
  }

  private int changePassword(Cookie session) throws Exception {
    return mockMvc
        .perform(
            post("/bff/auth/change-password")
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"Actual-2026\",\"newPassword\":\"Nueva-2026\"}"))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  private String register(String number) throws Exception {
    return mockMvc
        .perform(
            post("/bff/admin/guests")
                .cookie(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    ("{\"documentType\":\"DNI\",\"documentNumber\":\"%s\",\"type\":\"CONTRACT\","
                            + "\"accessMode\":\"TEMPORARY_PASSWORD\"}")
                        .formatted(number)))
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private int deactivate(String guestId) throws Exception {
    return mockMvc
        .perform(post("/bff/admin/guests/" + guestId + "/deactivate").cookie(admin()))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  private int login(String number, String password) throws Exception {
    return mockMvc
        .perform(
            post("/bff/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"documentType\":\"DNI\",\"documentNumber\":\"%s\",\"password\":\"%s\"}"
                        .formatted(number, password)))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  /** The ids as the session of the test shows them. */
  private MaskSession sessionIds() {
    return masker.session(sessionId);
  }
}
