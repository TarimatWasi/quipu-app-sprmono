package com.tarimatwasi.quipu.contract;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.tarimatwasi.quipu.auth.adapter.out.security.JwtTokenProvider;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.support.MutableClock;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.SynchronousBackgroundTasksConfig;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;

/**
 * TAR-36 (RF-11, RF-12, RF-17, RN-32): registering a guest with its first access, generating a new
 * one, activating the link and logging in with a temporary password, against the shared contract of
 * the tag pinned in pom.xml. The clock is a test clock: the 30 minutes are moved, not waited.
 */
// Many logins of one document in a minute: the allowance per account is not what this tests.
@SpringBootTest(
    properties = {
      "app.cors.allowed-origin=http://localhost:3000",
      "app.rate-limit.per-account-per-minute=1000"
    })
@AutoConfigureMockMvc
@ImportTestcontainers(PostgresContainers.class)
@ExtendWith(OutputCaptureExtension.class)
@Import(SynchronousBackgroundTasksConfig.class)
class GuestAccessContractTest {

  private static final String SPEC =
      java.nio.file.Path.of("target/contract/bff.yaml").toAbsolutePath().toString();
  private static final String GUESTS = "/bff/admin/guests";
  private static final String NEW_PASSWORD = "MiClave-2026";

  /** Its own clock, so that moving it does not touch the context of other tests. */
  @TestConfiguration
  static class TestClock {
    @Bean
    @Primary
    Clock accessClock() {
      return new MutableClock(Instant.parse("2026-10-14T14:00:00Z"));
    }
  }

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired JwtTokenProvider jwtTokenProvider;
  @Autowired IdMasker masker;
  @Autowired Clock clock;

  private final Long adminId = TestIds.next();
  private final Long otherGuestAccountId = TestIds.next();

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " must_change_password, status)"
            + " VALUES (?, 'admin@example.test', 'DNI', '00000001', 'hash', 'ADMIN', FALSE,"
            + " 'ACTIVE')",
        adminId);
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " must_change_password, status)"
            + " VALUES (?, NULL, 'DNI', '00000002', 'hash', 'GUEST', FALSE, 'ACTIVE')",
        otherGuestAccountId);
  }

  @AfterEach
  void tearDown() {
    TestTables.clear(jdbc);
  }

  private static ResultMatcher satisfiesTheContract() {
    return openApi().isValid(SPEC);
  }

  private Cookie admin() {
    return new Cookie("sessionToken", jwtTokenProvider.issue(adminId.toString()));
  }

  private MutableClock clock() {
    return (MutableClock) clock;
  }

  private ResultActions register(String number, String mode, @Nullable String email)
      throws Exception {
    String contact = email == null ? "" : ",\"contactEmail\":\"" + email + "\"";
    return mockMvc.perform(
        post(GUESTS)
            .cookie(admin())
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"documentType\":\"DNI\",\"documentNumber\":\"%s\",\"type\":\"CONTRACT\",\"accessMode\":\"%s\"%s}"
                    .formatted(number, mode, contact)));
  }

  private ResultActions generate(String guestId, String mode, Cookie session) throws Exception {
    return mockMvc.perform(
        post(GUESTS + "/" + guestId + "/access")
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accessMode\":\"%s\"}".formatted(mode)));
  }

  private ResultActions activate(String token, String password) throws Exception {
    return mockMvc.perform(
        post("/bff/auth/activate")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"%s\",\"newPassword\":\"%s\"}".formatted(token, password)));
  }

  private ResultActions login(String number, String password) throws Exception {
    return mockMvc.perform(
        post("/bff/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"documentType\":\"DNI\",\"documentNumber\":\"%s\",\"password\":\"%s\"}"
                    .formatted(number, password)));
  }

  private static String read(MvcResult result, String path) throws Exception {
    return JsonPath.read(result.getResponse().getContentAsString(), path);
  }

  private static String tokenOf(String activationUrl) {
    return activationUrl.substring(activationUrl.indexOf("token=") + "token=".length());
  }

  private String statusOf(String maskedGuestId) {
    return jdbc.queryForObject(
        "SELECT status FROM guests WHERE id = ?",
        String.class,
        masker.unmask(IdKind.GUEST, UUID.fromString(maskedGuestId)));
  }

  @Test
  void aLinkRegistrationAnswersTheContractUncacheableAndStoresOnlyTheHash() throws Exception {
    MvcResult created =
        register("12345678", "LINK", "ana@example.com")
            .andExpect(status().isCreated())
            .andExpect(satisfiesTheContract())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.alreadyRegistered").value(false))
            .andExpect(jsonPath("$.guest.status").value("PENDING_ACTIVATION"))
            .andExpect(jsonPath("$.access.mode").value("LINK"))
            .andExpect(jsonPath("$.access.temporaryPassword").doesNotExist())
            .andExpect(jsonPath("$.access.emailSent").value(false))
            .andReturn();
    String url = read(created, "$.access.activationUrl");
    assertThat(url).startsWith("http://localhost:3000/activate#token=");
    assertThat(read(created, "$.access.shareMessage")).contains(url).contains("30 minutos");
    assertThat(read(created, "$.access.expiresAt"))
        .isEqualTo(clock.instant().plus(Duration.ofMinutes(30)).toString());

    String token = tokenOf(url);
    assertThat(jdbc.queryForList("SELECT token_hash FROM access_grants", String.class))
        .hasSize(1)
        .allSatisfy(hash -> assertThat(hash).hasSize(64).doesNotContain(token));
    assertThat(
            jdbc.queryForObject(
                "SELECT role || ':' || email || ':' || must_change_password FROM users"
                    + " WHERE document_number = '12345678'",
                String.class))
        .isEqualTo("GUEST:ana@example.com:true");
    // The registered guest cannot log in before it uses the access.
    login("12345678", token).andExpect(status().isUnauthorized());
  }

  @Test
  void theLinkIsSingleUseAndAWeakPasswordDoesNotConsumeIt() throws Exception {
    MvcResult created = register("12345678", "LINK", null).andReturn();
    String guestId = read(created, "$.guest.id");
    String token = tokenOf(read(created, "$.access.activationUrl"));

    activate(token, "corta")
        .andExpect(status().isBadRequest())
        .andExpect(satisfiesTheContractToAnInvalidRequest())
        .andExpect(jsonPath("$.code").value("AUTH_WEAK_PASSWORD"))
        .andExpect(jsonPath("$.field").value("newPassword"));
    assertThat(statusOf(guestId)).isEqualTo("PENDING_ACTIVATION");

    activate(token, NEW_PASSWORD)
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(cookie().exists("sessionToken"))
        .andExpect(jsonPath("$.role").value("GUEST"))
        .andExpect(jsonPath("$.guestType").value("CONTRACT"))
        .andExpect(jsonPath("$.name").value("DNI 12345678"))
        .andExpect(jsonPath("$.mustChangePassword").value(false))
        .andExpect(jsonPath("$.guestStatus").value("ONBOARDING"))
        .andExpect(jsonPath("$.nextOnboardingStep").value("PERSONAL_DATA"));
    assertThat(statusOf(guestId)).isEqualTo("ONBOARDING");

    activate(token, NEW_PASSWORD)
        .andExpect(status().isBadRequest())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("AUTH_INVALID_OR_EXPIRED_CODE"));
    login("12345678", NEW_PASSWORD)
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.guestStatus").value("ONBOARDING"));
  }

  @Test
  void theLinkExpiresAfterThirtyMinutes() throws Exception {
    String token =
        tokenOf(read(register("12345678", "LINK", null).andReturn(), "$.access.activationUrl"));
    clock().advance(Duration.ofMinutes(30));

    activate(token, NEW_PASSWORD)
        .andExpect(status().isBadRequest())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("AUTH_INVALID_OR_EXPIRED_CODE"));
    activate("not-a-token", NEW_PASSWORD)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("AUTH_INVALID_OR_EXPIRED_CODE"));
  }

  @Test
  void aNewAccessRevokesThePreviousOne() throws Exception {
    MvcResult created = register("12345678", "LINK", null).andReturn();
    String guestId = read(created, "$.guest.id");
    String first = tokenOf(read(created, "$.access.activationUrl"));

    MvcResult again =
        generate(guestId, "LINK", admin())
            .andExpect(status().isOk())
            .andExpect(satisfiesTheContract())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.mode").value("LINK"))
            .andReturn();
    String second = tokenOf(read(again, "$.activationUrl"));

    activate(first, NEW_PASSWORD)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("AUTH_INVALID_OR_EXPIRED_CODE"));
    activate(second, NEW_PASSWORD).andExpect(status().isOk());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM access_grants WHERE revoked_at IS NOT NULL", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void aTemporaryPasswordLogsInPendingItsChangeAndIsConsumedWhenTheGuestChoosesOne()
      throws Exception {
    MvcResult created =
        register("12345678", "TEMPORARY_PASSWORD", null)
            .andExpect(status().isCreated())
            .andExpect(satisfiesTheContract())
            .andExpect(jsonPath("$.access.activationUrl").doesNotExist())
            .andReturn();
    String guestId = read(created, "$.guest.id");
    String temporary = read(created, "$.access.temporaryPassword");

    MvcResult session =
        login("12345678", temporary)
            .andExpect(status().isOk())
            .andExpect(satisfiesTheContract())
            .andExpect(jsonPath("$.mustChangePassword").value(true))
            .andExpect(jsonPath("$.guestStatus").value("ONBOARDING"))
            .andExpect(jsonPath("$.nextOnboardingStep").value("PASSWORD"))
            .andReturn();
    // D-30: logging in neither consumes the access nor changes the stored state.
    assertThat(statusOf(guestId)).isEqualTo("PENDING_ACTIVATION");
    login("12345678", temporary).andExpect(status().isOk());

    Cookie cookie = session.getResponse().getCookie("sessionToken");
    mockMvc
        .perform(get("/bff/auth/me").cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.mustChangePassword").value(true))
        .andExpect(jsonPath("$.nextOnboardingStep").value("PASSWORD"));
    changePassword(cookie, null, temporary)
        .andExpect(status().isBadRequest())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("AUTH_PASSWORD_UNCHANGED"));
    assertThat(statusOf(guestId)).isEqualTo("PENDING_ACTIVATION");

    changePassword(cookie, null, NEW_PASSWORD).andExpect(status().isNoContent());
    assertThat(statusOf(guestId)).isEqualTo("ONBOARDING");

    login("12345678", temporary).andExpect(status().isUnauthorized());
    login("12345678", NEW_PASSWORD)
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.mustChangePassword").value(false));
  }

  private ResultActions changePassword(Cookie session, @Nullable String current, String next)
      throws Exception {
    String currentField = current == null ? "" : "\"currentPassword\":\"" + current + "\",";
    return mockMvc.perform(
        post("/bff/auth/change-password")
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{%s\"newPassword\":\"%s\"}".formatted(currentField, next)));
  }

  /** Only the session opened with the temporary password skips the current one. */
  @Test
  void anEarlierSessionStillNeedsTheCurrentPasswordAfterATemporaryLogin() throws Exception {
    MvcResult created = register("12345678", "LINK", null).andReturn();
    String guestId = read(created, "$.guest.id");
    Cookie earlier =
        activate(tokenOf(read(created, "$.access.activationUrl")), NEW_PASSWORD)
            .andReturn()
            .getResponse()
            .getCookie("sessionToken");
    String temporary =
        read(generate(guestId, "TEMPORARY_PASSWORD", admin()).andReturn(), "$.temporaryPassword");
    Cookie temporarySession =
        login("12345678", temporary).andReturn().getResponse().getCookie("sessionToken");

    changePassword(earlier, null, "Otra-clave-2026")
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
    changePassword(temporarySession, null, "Otra-clave-2026").andExpect(status().isNoContent());
    login("12345678", "Otra-clave-2026").andExpect(status().isOk());
  }

  /** RN-12: a deactivated guest loses the access it was handed; reactivating does not revive it. */
  @Test
  void deactivatingTheGuestRevokesItsAccess() throws Exception {
    MvcResult created = register("12345678", "LINK", null).andReturn();
    String guestId = read(created, "$.guest.id");
    String token = tokenOf(read(created, "$.access.activationUrl"));

    mockMvc
        .perform(post(GUESTS + "/" + guestId + "/deactivate").cookie(admin()))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(post(GUESTS + "/" + guestId + "/reactivate").cookie(admin()))
        .andExpect(status().isOk());

    activate(token, NEW_PASSWORD)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("AUTH_INVALID_OR_EXPIRED_CODE"));
  }

  /** RF-11: a guest pending activation gets its first password only through its access. */
  @Test
  void theRecoveryDoesNothingForAGuestPendingActivation() throws Exception {
    register("12345678", "LINK", "ana@example.com").andExpect(status().isCreated());

    mockMvc
        .perform(
            post("/bff/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"ana@example.com\"}"))
        .andExpect(status().isAccepted());

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE reset_token_hash IS NOT NULL", Integer.class))
        .isZero();
  }

  @Test
  void anExpiredTemporaryPasswordAnswersItsOwnCodeOnlyWhenItMatched() throws Exception {
    String temporary =
        read(
            register("12345678", "TEMPORARY_PASSWORD", null).andReturn(),
            "$.access.temporaryPassword");
    clock().advance(Duration.ofMinutes(30));

    login("12345678", temporary)
        .andExpect(status().isUnauthorized())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("AUTH_TEMPORARY_PASSWORD_EXPIRED"));
    login("12345678", "Otra-clave-1")
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
  }

  @Test
  void anExistingDocumentIsAnsweredUnchangedAndWithoutAccess() throws Exception {
    register("12345678", "LINK", null).andExpect(status().isCreated());

    register("12345678", "TEMPORARY_PASSWORD", null)
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.alreadyRegistered").value(true))
        .andExpect(jsonPath("$.access").doesNotExist());
    assertThat(jdbc.queryForObject("SELECT count(*) FROM access_grants", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void theConflictsOfARegistrationAnswerTheContract() throws Exception {
    MvcResult created = register("12345678", "LINK", "ana@example.com").andReturn();
    String guestId = read(created, "$.guest.id");

    register("87654321", "LINK", "ANA@example.com")
        .andExpect(status().isConflict())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("GUEST_EMAIL_TAKEN"));
    register("00000001", "LINK", null)
        .andExpect(status().isConflict())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("GUEST_DOCUMENT_TAKEN"));

    jdbc.update(
        "UPDATE guests SET status = 'INACTIVE', status_before_inactive = 'PENDING_ACTIVATION'");
    register("12345678", "LINK", null)
        .andExpect(status().isConflict())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("GUEST_INACTIVE"))
        .andExpect(jsonPath("$.guestId").value(guestId));
    generate(guestId, "LINK", admin())
        .andExpect(status().isConflict())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("GUEST_INACTIVE"))
        .andExpect(jsonPath("$.guestId").value(guestId));
  }

  @Test
  void aGuestSessionCannotUseTheAdministratorRoutes() throws Exception {
    String guestId = read(register("12345678", "LINK", null).andReturn(), "$.guest.id");
    Cookie guest =
        new Cookie("sessionToken", jwtTokenProvider.issue(otherGuestAccountId.toString()));

    mockMvc
        .perform(
            post(GUESTS)
                .cookie(guest)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"documentType\":\"DNI\",\"documentNumber\":\"87654321\",\"type\":\"CONTRACT\",\"accessMode\":\"LINK\"}"))
        .andExpect(status().isForbidden())
        .andExpect(satisfiesTheContract());
    generate(guestId, "LINK", guest)
        .andExpect(status().isForbidden())
        .andExpect(satisfiesTheContract());
  }

  /** The secrets are shown once: never in the logs nor in a later answer. */
  @Test
  void theSecretsReachNoLogAndNoLaterAnswer(CapturedOutput output) throws Exception {
    MvcResult link = register("12345678", "LINK", "ana@example.com").andReturn();
    String guestId = read(link, "$.guest.id");
    String url = read(link, "$.access.activationUrl");
    String temporary =
        read(generate(guestId, "TEMPORARY_PASSWORD", admin()).andReturn(), "$.temporaryPassword");
    login("12345678", temporary).andExpect(status().isOk());

    String detail =
        mockMvc
            .perform(get(GUESTS + "/" + guestId).cookie(admin()))
            .andExpect(status().isOk())
            .andExpect(satisfiesTheContract())
            .andExpect(
                jsonPath("$.accessExpiresAt")
                    .value(clock.instant().plus(Duration.ofMinutes(30)).toString()))
            .andReturn()
            .getResponse()
            .getContentAsString();

    for (String secret : new String[] {url, tokenOf(url), temporary}) {
      assertThat(output.getAll()).doesNotContain(secret);
      assertThat(detail).doesNotContain(secret);
    }
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
}
