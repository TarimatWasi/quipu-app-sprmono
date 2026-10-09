package com.tarimatwasi.quipu.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tarimatwasi.quipu.auth.port.out.PasswordResetMailPort;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import jakarta.servlet.http.Cookie;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** RF-16 end to end: the real database and security chain, with the email provider replaced. */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@AutoConfigureMockMvc
@ImportTestcontainers(PostgresContainers.class)
class PasswordRecoveryBffTest {

  private static final String FORGOT = "/bff/auth/forgot-password";
  private static final String RESET = "/bff/auth/reset-password";
  private static final String LOGIN = "/bff/auth/login";
  private static final String EMAIL = "guest@example.test";
  private static final String OLD_PASSWORD = "Antigua12345";
  private static final String NEW_PASSWORD = "Nueva12345";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder passwordEncoder;
  @MockitoBean PasswordResetMailPort mail;

  /** The PostgreSQL container is shared by all integration tests: this test owns its users. */
  @BeforeEach
  void oneGuestWithAPendingPasswordChange() {
    org.mockito.Mockito.reset(mail);
    TestTables.clear(jdbc);
    insertUser(EMAIL, "11111111", "ACTIVE", true);
  }

  private void insertUser(String email, String document, String status, boolean mustChange) {
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " must_change_password, status) VALUES (?, ?, 'DNI', ?, ?, 'GUEST', ?, ?)",
        TestIds.next(),
        email,
        document,
        passwordEncoder.encode(OLD_PASSWORD),
        mustChange,
        status);
  }

  private ResultActions postJson(String uri, String body) throws Exception {
    return mockMvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON).content(body));
  }

  private ResultActions login(String password) throws Exception {
    return postJson(
        LOGIN,
        "{\"documentType\":\"DNI\",\"documentNumber\":\"11111111\",\"password\":\"%s\"}"
            .formatted(password));
  }

  private String requestCode() throws Exception {
    postJson(FORGOT, "{\"email\":\"" + EMAIL + "\"}").andExpect(status().isAccepted());
    var code = ArgumentCaptor.forClass(String.class);
    verify(mail).sendResetLink(eq(EMAIL), code.capture(), eq(Duration.ofMinutes(30)));
    return code.getValue();
  }

  private ResultActions resetWith(String code, String password) throws Exception {
    return postJson(RESET, "{\"code\":\"%s\",\"newPassword\":\"%s\"}".formatted(code, password));
  }

  @Test
  void theWholeFlowSetsANewPasswordThatWorksAndTheOldOneDoesNot() throws Exception {
    String code = requestCode();

    resetWith(code, NEW_PASSWORD).andExpect(status().isNoContent()).andExpect(content().string(""));

    login(OLD_PASSWORD).andExpect(status().isUnauthorized());
    login(NEW_PASSWORD)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mustChangePassword").value(false));
  }

  @Test
  void onlyTheHashOfTheCodeIsStoredAndItExpiresInThirtyMinutes() throws Exception {
    String code = requestCode();

    String hash = jdbc.queryForObject("SELECT reset_token_hash FROM users", String.class);
    assertThat(hash).hasSize(64).matches("[0-9a-f]+").doesNotContain(code);
    Long minutes =
        jdbc.queryForObject(
            "SELECT EXTRACT(EPOCH FROM (reset_token_expires_at - now()))::bigint / 60 FROM users",
            Long.class);
    assertThat(minutes).isBetween(28L, 30L);
  }

  @Test
  void theCodeIsSingleUseAndIsRemovedFromTheDatabase() throws Exception {
    String code = requestCode();
    resetWith(code, NEW_PASSWORD).andExpect(status().isNoContent());

    resetWith(code, "Otra1234567")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("AUTH_INVALID_OR_EXPIRED_CODE"));
    assertThat(jdbc.queryForObject("SELECT reset_token_hash FROM users", String.class)).isNull();
  }

  /** Two simultaneous resets with the same code: the row lock lets exactly one of them win. */
  @Test
  void twoSimultaneousResetsWithTheSameCodeLetOnlyOneThrough() throws Exception {
    String code = requestCode();
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<Integer>> results = new ArrayList<>();
      for (String password : List.of("Primera12345", "Segunda12345")) {
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  return resetWith(code, password).andReturn().getResponse().getStatus();
                }));
      }
      start.countDown();
      List<Integer> statuses = new ArrayList<>();
      for (Future<Integer> result : results) {
        statuses.add(result.get(30, TimeUnit.SECONDS));
      }

      assertThat(statuses).containsExactlyInAnyOrder(204, 400);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void changingThePasswordKillsARecoveryCodeEmailedBefore() throws Exception {
    String code = requestCode();
    Cookie session =
        Objects.requireNonNull(
            login(OLD_PASSWORD).andReturn().getResponse().getCookie("sessionToken"));
    mockMvc
        .perform(
            post("/bff/auth/change-password")
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
        .andExpect(status().isNoContent());

    resetWith(code, "Otra1234567")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("AUTH_INVALID_OR_EXPIRED_CODE"));
  }

  @Test
  void anEmailThatDiffersOnlyInCaseCannotBeRegisteredTwice() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> insertUser("GUEST@example.test", "22222222", "ACTIVE", false))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  @Test
  void aRecoveryCodeBelongsToOneAccountOnly() {
    insertUser("other@example.test", "22222222", "ACTIVE", false);
    jdbc.update(
        "UPDATE users SET reset_token_hash = 'abc', reset_token_expires_at = now() WHERE email = ?",
        EMAIL);

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE users SET reset_token_hash = 'abc' WHERE email = ?",
                    "other@example.test"))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  /** A tiny table is scanned by choice; with scans disabled the planner shows what it could use. */
  @Test
  void theLookupByCodeCanUseAnIndexInsteadOfScanningTheTable() {
    String plan =
        jdbc.execute(
            (Connection connection) -> {
              try (Statement statement = connection.createStatement()) {
                statement.execute("SET enable_seqscan = off");
                var lines = new StringBuilder();
                try (ResultSet rows =
                    statement.executeQuery(
                        "EXPLAIN SELECT id FROM users WHERE reset_token_hash = 'abc'")) {
                  while (rows.next()) {
                    lines.append(rows.getString(1)).append(System.lineSeparator());
                  }
                } finally {
                  statement.execute("RESET enable_seqscan");
                }
                return lines.toString();
              }
            });

    assertThat(plan).contains("uq_users_reset_token_hash");
  }

  @Test
  void anExpiredCodeIsRefused() throws Exception {
    String code = requestCode();
    jdbc.update("UPDATE users SET reset_token_expires_at = now() - interval '1 minute'");

    resetWith(code, NEW_PASSWORD)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("AUTH_INVALID_OR_EXPIRED_CODE"))
        .andExpect(jsonPath("$.message").value("Enlace inválido o expirado, solicita uno nuevo"));
    login(OLD_PASSWORD).andExpect(status().isOk());
  }

  @Test
  void aWeakPasswordIsRefusedAndTheCodeStillWorks() throws Exception {
    String code = requestCode();

    resetWith(code, "corta")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("AUTH_WEAK_PASSWORD"))
        .andExpect(jsonPath("$.field").value("newPassword"));
    resetWith(code, NEW_PASSWORD).andExpect(status().isNoContent());
  }

  @Test
  void anUnknownEmailGetsTheSame202AndNoEmailIsSent() throws Exception {
    postJson(FORGOT, "{\"email\":\"nobody@example.test\"}")
        .andExpect(status().isAccepted())
        .andExpect(content().string(""));

    verifyNoInteractions(mail);
  }

  @Test
  void aDisabledAccountGetsTheSame202AndNoEmailIsSent() throws Exception {
    jdbc.update("UPDATE users SET status = 'INACTIVE'");

    postJson(FORGOT, "{\"email\":\"" + EMAIL + "\"}").andExpect(status().isAccepted());

    verifyNoInteractions(mail);
  }

  @Test
  void aSecondRequestRightAfterDoesNotSendAnotherEmail() throws Exception {
    requestCode();

    postJson(FORGOT, "{\"email\":\"" + EMAIL + "\"}").andExpect(status().isAccepted());

    verify(mail).sendResetLink(any(), any(), any());
  }

  @Test
  void aMalformedOrMissingEmailIsAValidationError() throws Exception {
    postJson(FORGOT, "{\"email\":\"not-an-email\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.field").value("email"));
    postJson(FORGOT, "{}").andExpect(status().isBadRequest());
    verifyNoInteractions(mail);
  }

  @Test
  void aMissingCodeOrPasswordIsAValidationError() throws Exception {
    postJson(RESET, "{\"newPassword\":\"" + NEW_PASSWORD + "\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    postJson(RESET, "{\"code\":\"abc\"}").andExpect(status().isBadRequest());
  }

  /**
   * The person forgot the password, so a leftover session that must change it cannot block this.
   */
  @Test
  void aSessionThatMustChangeItsPasswordCanStillRecover() throws Exception {
    Cookie pending =
        Objects.requireNonNull(
            login(OLD_PASSWORD).andReturn().getResponse().getCookie("sessionToken"));

    mockMvc
        .perform(
            post(FORGOT)
                .cookie(pending)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + EMAIL + "\"}"))
        .andExpect(status().isAccepted());
  }
}
