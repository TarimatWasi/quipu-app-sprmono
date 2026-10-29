package com.tarimatwasi.quipu.contract;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.tarimatwasi.quipu.auth.application.port.out.GuestAccountEmailNoticePort;
import com.tarimatwasi.quipu.auth.application.port.out.PasswordResetMailPort;
import com.tarimatwasi.quipu.auth.infrastructure.adapter.out.security.JwtTokenProvider;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.shared.masking.MaskSession;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.SessionIds;
import com.tarimatwasi.quipu.support.SynchronousBackgroundTasksConfig;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;

/**
 * TAR-229 (RF-02, RF-16, D-76, D-77): the account email of a guest, refused at the registration
 * when another account has it and set by the administrator, against the real database, the real
 * security chain and the contract pinned in pom.xml. The email provider is replaced.
 */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@AutoConfigureMockMvc
@ImportTestcontainers(PostgresContainers.class)
@ExtendWith(OutputCaptureExtension.class)
@Import(SynchronousBackgroundTasksConfig.class)
class GuestAccountEmailContractTest {

  private static final String SPEC =
      java.nio.file.Path.of("target/contract/bff.yaml").toAbsolutePath().toString();
  private static final String GUESTS = "/bff/admin/guests";
  private static final String ADMIN_EMAIL = "admin@example.com";
  private static final String OTHER_ACCOUNT_EMAIL = "beto@example.com";
  private static final int RACE_ROUNDS = 8;

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired JwtTokenProvider jwtTokenProvider;
  @Autowired IdMasker masker;
  @MockitoBean PasswordResetMailPort resetMail;
  @MockitoBean GuestAccountEmailNoticePort notices;

  /** TAR-227: every account of a test signs in to this one session. */
  private final String sessionId = SessionIds.newSessionId();

  private final Long adminId = TestIds.next();
  private final Long otherAccountId = TestIds.next();

  @BeforeEach
  void setUp() {
    Mockito.reset(resetMail, notices);
    TestTables.clear(jdbc);
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " must_change_password, status)"
            + " VALUES (?, ?, 'DNI', '00000001', 'hash', 'ADMIN', FALSE, 'ACTIVE')",
        adminId,
        ADMIN_EMAIL);
    // An account of no registered guest, so its email is an account email and no contact email.
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " must_change_password, status)"
            + " VALUES (?, ?, 'DNI', '00000002', 'hash', 'GUEST', FALSE, 'ACTIVE')",
        otherAccountId,
        OTHER_ACCOUNT_EMAIL);
  }

  @AfterEach
  void tearDown() {
    TestTables.clear(jdbc);
  }

  private static ResultMatcher satisfiesTheContract() {
    return openApi().isValid(SPEC);
  }

  private static ResultMatcher satisfiesTheContractToAnInvalidRequest() {
    return openApi()
        .isValid(
            com.atlassian.oai.validator.OpenApiInteractionValidator.createFor(SPEC)
                .withWhitelist(
                    com.atlassian.oai.validator.whitelist.ValidationErrorsWhitelist.create()
                        .withRule(
                            "the request is invalid on purpose",
                            com.atlassian.oai.validator.whitelist.rule.WhitelistRules.isRequest()))
                .build());
  }

  private Cookie admin() {
    return session(adminId);
  }

  private Cookie session(Long accountId) {
    return new Cookie("sessionToken", jwtTokenProvider.renew(accountId.toString(), sessionId));
  }

  private ResultActions register(String number, @Nullable String email) throws Exception {
    String contact = email == null ? "" : ",\"contactEmail\":\"" + email + "\"";
    return mockMvc.perform(
        post(GUESTS)
            .cookie(admin())
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"documentType\":\"DNI\",\"documentNumber\":\"%s\",\"type\":\"CONTRACT\",\"accessMode\":\"LINK\"%s}"
                    .formatted(number, contact)));
  }

  /** A registered guest whose account already chose its password, so it can recover it. */
  private String registeredGuest(String number, @Nullable String email) throws Exception {
    String body =
        register(number, email)
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String guestId = JsonPath.read(body, "$.guest.id");
    jdbc.update(
        "UPDATE users SET must_change_password = FALSE WHERE guest_id = ?", guestOf(guestId));
    return guestId;
  }

  private ResultActions setEmail(String guestId, String body, Cookie session) throws Exception {
    return mockMvc.perform(
        put(GUESTS + "/" + guestId + "/account-email")
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private ResultActions setEmail(String guestId, String email) throws Exception {
    return setEmail(guestId, "{\"email\":\"" + email + "\"}", admin());
  }

  private ResultActions forgot(String email) throws Exception {
    return mockMvc.perform(
        post("/bff/auth/forgot-password")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\"}"));
  }

  private Long guestOf(String maskedId) {
    return sessionIds().unmask(IdKind.GUEST, UUID.fromString(maskedId));
  }

  private @Nullable String accountEmailOf(String maskedGuestId) {
    return jdbc.queryForObject(
        "SELECT email FROM users WHERE guest_id = ?", String.class, guestOf(maskedGuestId));
  }

  private int count(String sql) {
    Integer count = jdbc.queryForObject(sql, Integer.class);
    return count == null ? 0 : count;
  }

  private static String sha256(String value) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void aRegistrationWithTheAccountEmailOfAnAdministratorCreatesNothing() throws Exception {
    register("12345678", "ADMIN@Example.com")
        .andExpect(status().isConflict())
        .andExpect(satisfiesTheContract())
        .andExpect(
            content()
                .json(
                    "{\"code\":\"ACCOUNT_EMAIL_TAKEN\",\"message\":\"Ese correo ya pertenece a una"
                        + " cuenta\",\"field\":\"contactEmail\"}",
                    JsonCompareMode.STRICT));

    assertThat(count("SELECT count(*) FROM guests")).isZero();
    assertThat(count("SELECT count(*) FROM users")).isEqualTo(2);
    assertThat(count("SELECT count(*) FROM access_grants")).isZero();
  }

  @Test
  void aRegistrationWithTheAccountEmailOfAnotherGuestAccountCreatesNothing() throws Exception {
    register("12345678", "Beto@example.com")
        .andExpect(status().isConflict())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("ACCOUNT_EMAIL_TAKEN"))
        .andExpect(jsonPath("$.field").value("contactEmail"));

    assertThat(count("SELECT count(*) FROM guests")).isZero();
    assertThat(count("SELECT count(*) FROM users WHERE document_number = '12345678'")).isZero();
    assertThat(count("SELECT count(*) FROM access_grants")).isZero();
    // The same document registers once the email is a free one.
    register("12345678", "libre@example.com").andExpect(status().isCreated());
    assertThat(count("SELECT count(*) FROM users WHERE lower(email) = 'libre@example.com'"))
        .isEqualTo(1);
  }

  @Test
  void settingTheEmailOfAGuestWithoutOneMakesTheRecoveryWriteToIt() throws Exception {
    String guestId = registeredGuest("12345678", null);

    setEmail(guestId, "nueva@example.com")
        .andExpect(status().isNoContent())
        .andExpect(satisfiesTheContract())
        .andExpect(content().string(""));

    assertThat(accountEmailOf(guestId)).isEqualTo("nueva@example.com");
    forgot("nueva@example.com").andExpect(status().isAccepted());
    verify(resetMail).sendResetLink(eq("nueva@example.com"), anyString(), any(Duration.class));
    // No previous email, so nobody is told.
    verifyNoInteractions(notices);
  }

  @Test
  void aChangeKillsThePendingCodeTellsThePreviousEmailAndMovesTheRecovery(CapturedOutput output)
      throws Exception {
    String guestId = registeredGuest("12345678", "ana@example.com");
    String code = "codigo-previo-de-recuperacion";
    jdbc.update(
        "UPDATE users SET reset_token_hash = ?, reset_token_expires_at = ? WHERE guest_id = ?",
        sha256(code),
        Timestamp.from(Instant.now().plus(Duration.ofMinutes(30))),
        guestOf(guestId));

    setEmail(guestId, "ana.nueva@example.com")
        .andExpect(status().isNoContent())
        .andExpect(satisfiesTheContract());

    assertThat(accountEmailOf(guestId)).isEqualTo("ana.nueva@example.com");
    assertThat(
            count(
                "SELECT count(*) FROM users WHERE reset_token_hash IS NOT NULL"
                    + " OR reset_token_expires_at IS NOT NULL"))
        .isZero();
    mockMvc
        .perform(
            post("/bff/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"%s\",\"newPassword\":\"Nueva-Clave-2026\"}".formatted(code)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("AUTH_INVALID_OR_EXPIRED_CODE"));
    verify(notices).accountEmailChanged("ana@example.com", "ana.nueva@example.com");

    forgot("ana@example.com").andExpect(status().isAccepted());
    verify(resetMail, never()).sendResetLink(eq("ana@example.com"), anyString(), any());
    forgot("ana.nueva@example.com").andExpect(status().isAccepted());
    verify(resetMail).sendResetLink(eq("ana.nueva@example.com"), anyString(), any());
    // Neither email reaches the logs.
    assertThat(output.getAll()).doesNotContain("ana.nueva@example.com").doesNotContain(code);
  }

  @Test
  void theSameEmailInAnyCaseAnswers204AndChangesNothing() throws Exception {
    String guestId = registeredGuest("12345678", "ana@example.com");
    String code = "codigo-que-sigue-vivo";
    jdbc.update(
        "UPDATE users SET reset_token_hash = ?, reset_token_expires_at = ? WHERE guest_id = ?",
        sha256(code),
        Timestamp.from(Instant.now().plus(Duration.ofMinutes(30))),
        guestOf(guestId));

    setEmail(guestId, "ana@example.com")
        .andExpect(status().isNoContent())
        .andExpect(satisfiesTheContract());
    setEmail(guestId, "ANA@Example.com").andExpect(status().isNoContent());

    assertThat(accountEmailOf(guestId)).isEqualTo("ana@example.com");
    assertThat(count("SELECT count(*) FROM users WHERE reset_token_hash IS NOT NULL")).isEqualTo(1);
    verifyNoInteractions(notices);
  }

  @Test
  void anEmailOfAnotherAccountInAnyCaseIsAConflictAndNothingChanges() throws Exception {
    String guestId = registeredGuest("12345678", "ana@example.com");
    String other = registeredGuest("87654321", "carla@example.com");

    setEmail(guestId, "Admin@EXAMPLE.com")
        .andExpect(status().isConflict())
        .andExpect(satisfiesTheContract())
        .andExpect(
            content()
                .json(
                    "{\"code\":\"ACCOUNT_EMAIL_TAKEN\",\"message\":\"Ese correo ya pertenece a una"
                        + " cuenta\",\"field\":\"email\"}",
                    JsonCompareMode.STRICT));
    setEmail(guestId, "CARLA@example.com")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ACCOUNT_EMAIL_TAKEN"))
        .andExpect(jsonPath("$.field").value("email"));
    setEmail(guestId, OTHER_ACCOUNT_EMAIL)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ACCOUNT_EMAIL_TAKEN"));

    assertThat(accountEmailOf(guestId)).isEqualTo("ana@example.com");
    assertThat(accountEmailOf(other)).isEqualTo("carla@example.com");
    verifyNoInteractions(notices);
  }

  @Test
  void anInactiveGuestIsAConflictWithItsId() throws Exception {
    String guestId = registeredGuest("12345678", "ana@example.com");
    jdbc.update(
        "UPDATE guests SET status = 'INACTIVE', status_before_inactive = 'PENDING_ACTIVATION'");

    setEmail(guestId, "ana.nueva@example.com")
        .andExpect(status().isConflict())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("GUEST_INACTIVE"))
        .andExpect(jsonPath("$.guestId").value(guestId));

    assertThat(accountEmailOf(guestId)).isEqualTo("ana@example.com");
    verifyNoInteractions(notices);
  }

  /**
   * TAR-235: every guest is created with its account, so one without it is corrupt data: an
   * internal error, not a business 409, and nothing is written.
   */
  @Test
  void aGuestWithoutAccountIsAnInternalError() throws Exception {
    String guestId = registeredGuest("12345678", null);
    jdbc.update("DELETE FROM users WHERE guest_id = ?", guestOf(guestId));

    assertThatThrownBy(() -> setEmail(guestId, "nueva@example.com"))
        .hasRootCauseInstanceOf(IllegalStateException.class);
    assertThat(count("SELECT count(*) FROM users WHERE lower(email) = 'nueva@example.com'"))
        .isZero();
  }

  @Test
  void anIdOfAnotherSessionOrNeverIssuedIsNotFound() throws Exception {
    String guestId = registeredGuest("12345678", "ana@example.com");
    String elsewhere =
        masker.session(SessionIds.newSessionId()).mask(IdKind.GUEST, guestOf(guestId)).toString();

    setEmail(elsewhere, "ana.nueva@example.com")
        .andExpect(status().isNotFound())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("GUEST_NOT_FOUND"));
    setEmail(UUID.randomUUID().toString(), "ana.nueva@example.com")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("GUEST_NOT_FOUND"));

    assertThat(accountEmailOf(guestId)).isEqualTo("ana@example.com");
  }

  @Test
  void onlyAnAdministratorSessionReachesIt() throws Exception {
    String guestId = registeredGuest("12345678", "ana@example.com");

    setEmail(guestId, "{\"email\":\"x@example.com\"}", session(otherAccountId))
        .andExpect(status().isForbidden())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    mockMvc
        .perform(
            put(GUESTS + "/" + guestId + "/account-email")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"x@example.com\"}"))
        .andExpect(status().isUnauthorized())
        // The request lacks the cookie on purpose; the answer must still satisfy the contract.
        .andExpect(satisfiesTheContractToAnInvalidRequest())
        .andExpect(jsonPath("$.code").value("AUTH_NO_SESSION"));

    assertThat(accountEmailOf(guestId)).isEqualTo("ana@example.com");
  }

  @Test
  void anInvalidBodyIsAValidationError() throws Exception {
    String guestId = registeredGuest("12345678", "ana@example.com");
    String tooLong = "a".repeat(190) + "@example.com";

    for (String body :
        List.of(
            "{}",
            "{\"email\":\"\"}",
            "{\"email\":\"no-es-correo\"}",
            "{\"email\":\"" + tooLong + "\"}")) {
      setEmail(guestId, body, admin())
          .andExpect(status().isBadRequest())
          .andExpect(satisfiesTheContractToAnInvalidRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
          .andExpect(jsonPath("$.field").value("email"));
    }
    assertThat(accountEmailOf(guestId)).isEqualTo("ana@example.com");
  }

  /** Two guests take the same email at once: the unique index lets exactly one have it. */
  @Test
  void twoSimultaneousChangesToTheSameEmailLeaveItToOnlyOneAccount() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int round = 0; round < RACE_ROUNDS; round++) {
        String first = registeredGuest(String.format("7100%04d", round), null);
        String second = registeredGuest(String.format("7200%04d", round), null);
        String email = "carrera" + round + "@example.com";
        CountDownLatch start = new CountDownLatch(1);
        Future<Integer> one =
            pool.submit(
                () -> {
                  start.await();
                  return setEmail(first, email).andReturn().getResponse().getStatus();
                });
        Future<Integer> other =
            pool.submit(
                () -> {
                  start.await();
                  return setEmail(second, email.toUpperCase(java.util.Locale.ROOT))
                      .andReturn()
                      .getResponse()
                      .getStatus();
                });
        start.countDown();

        assertThat(List.of(one.get(20, TimeUnit.SECONDS), other.get(20, TimeUnit.SECONDS)))
            .containsExactlyInAnyOrder(204, 409);
        assertThat(
                count(
                    "SELECT count(*) FROM users WHERE lower(email) = '"
                        + email.toLowerCase(java.util.Locale.ROOT)
                        + "'"))
            .isEqualTo(1);
      }
    } finally {
      pool.shutdownNow();
    }
  }

  /** The ids as the session of the test shows them. */
  private MaskSession sessionIds() {
    return masker.session(sessionId);
  }
}
