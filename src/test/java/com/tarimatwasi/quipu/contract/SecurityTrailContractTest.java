package com.tarimatwasi.quipu.contract;

import static com.tarimatwasi.quipu.support.BrowserCsrf.postWithCsrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.tarimatwasi.quipu.auth.application.port.in.PasswordRecoveryUseCase;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.awaitility.Awaitility;
import org.jspecify.annotations.Nullable;
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
 * The security trail: who entered and who asked to recover an account reach the audit, with no
 * secret in it (no sid, no token, no recovery code).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ImportTestcontainers(PostgresContainers.class)
class SecurityTrailContractTest {
  private static final String SESSION_OPENED = "com.tarimatwasi.quipu.auth.session-opened.v1";
  private static final String RESET_ISSUED = "com.tarimatwasi.quipu.auth.password-reset-issued.v1";
  private static final String PASSWORD = "Temporal123!";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired PasswordRecoveryUseCase recovery;

  private Long adminId;

  @BeforeEach
  void anAdministrator() {
    TestTables.clear(jdbc);
    adminId = TestIds.next();
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " must_change_password, status)"
            + " VALUES (?, 'trail@example.test', 'DNI', '00000001', ?, 'ADMIN', FALSE, 'ACTIVE')",
        adminId,
        passwordEncoder.encode(PASSWORD));
  }

  private void login(String password, int status) throws Exception {
    mockMvc
        .perform(
            postWithCsrf("/bff/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"documentType\":\"DNI\",\"documentNumber\":\"00000001\",\"password\":\""
                        + password
                        + "\"}"))
        .andExpect(status().is(status));
  }

  private List<Map<String, @Nullable Object>> trail(String type) {
    return jdbc.queryForList("SELECT * FROM audit_event WHERE type = ?", type);
  }

  private Map<String, @Nullable Object> awaitOne(String type) {
    return Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> trail(type), rows -> rows.size() == 1)
        .getFirst();
  }

  private void awaitOutboxDrained() {
    Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .until(
            () ->
                jdbc.queryForObject(
                    "SELECT count(*) FROM event_publication WHERE completion_date IS NULL",
                    Integer.class),
            pending -> pending != null && pending == 0);
  }

  @Test
  void login_isAuditedOnlyWhenItOpensASession() throws Exception {
    login("Wrong123!", 401);
    login(PASSWORD, 200);

    Map<String, @Nullable Object> entry = awaitOne(SESSION_OPENED);
    awaitOutboxDrained();
    assertThat(trail(SESSION_OPENED)).hasSize(1);
    Long sessionId =
        jdbc.queryForObject("SELECT id FROM sessions WHERE user_id = ?", Long.class, adminId);
    assertThat(entry).containsEntry("subject", "session/" + sessionId);
    String data = String.valueOf(entry.get("data"));
    assertThat(JsonPath.<Number>read(data, "$.userId").longValue()).isEqualTo(adminId);
    assertThat(JsonPath.<Boolean>read(data, "$.temporary")).isFalse();
    assertThat(data).doesNotContainIgnoringCase("sid").doesNotContain("token");
  }

  @Test
  void recoveryRequest_isAuditedWithoutItsCode() {
    // The request runs on the shared background executor, which drops work at its limit (other
    // tests of this context may have filled it): it is asked again on every poll, as a person
    // would, and a request inside the keep window issues nothing new.
    Map<String, @Nullable Object> entry =
        Awaitility.await()
            .atMost(Duration.ofSeconds(20))
            .pollInterval(Duration.ofMillis(500))
            .until(
                () -> {
                  recovery.requestReset("trail@example.test");
                  recovery.requestReset("nobody@example.test");
                  return trail(RESET_ISSUED);
                },
                rows -> !rows.isEmpty())
            .getFirst();
    awaitOutboxDrained();
    assertThat(trail(RESET_ISSUED)).hasSize(1);
    assertThat(entry).containsEntry("subject", "user/" + adminId);
    String data = String.valueOf(entry.get("data"));
    String codeHash =
        jdbc.queryForObject(
            "SELECT reset_token_hash FROM users WHERE id = ?", String.class, adminId);
    assertThat(data).contains("expiresAt").doesNotContain(String.valueOf(codeHash));
  }
}
