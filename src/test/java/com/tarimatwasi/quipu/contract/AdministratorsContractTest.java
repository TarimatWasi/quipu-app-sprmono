package com.tarimatwasi.quipu.contract;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.whitelist.ValidationErrorsWhitelist;
import com.atlassian.oai.validator.whitelist.rule.WhitelistRules;
import com.jayway.jsonpath.JsonPath;
import com.tarimatwasi.quipu.auth.adapter.out.security.JwtTokenProvider;
import com.tarimatwasi.quipu.auth.port.out.AdministratorNoticePort;
import com.tarimatwasi.quipu.auth.port.out.PasswordResetMailPort;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import jakarta.servlet.http.Cookie;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.ResultMatcher;

/**
 * TAR-188 (RF-22, CU-30): the real answers of creating an administrator satisfy the shared
 * contract, the one of the tag pinned in pom.xml (contract.version), and the account created can
 * choose its password with the link and log in.
 */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@AutoConfigureMockMvc
@ImportTestcontainers(PostgresContainers.class)
class AdministratorsContractTest {

  private static final String SPEC =
      Path.of("target/contract/bff.yaml").toAbsolutePath().toString();
  private static final String URL = "/bff/admin/administrators";
  private static final String PASSWORD = "Creador12345";
  private static final String NEW_DOCUMENT = "22222222";
  private static final String NEW_EMAIL = "nueva.admin@example.com";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired JwtTokenProvider jwtTokenProvider;
  @Autowired IdMasker ids;
  @Autowired PasswordEncoder passwordEncoder;
  @MockitoBean PasswordResetMailPort mail;
  @MockitoBean AdministratorNoticePort notices;

  private final Long adminId = TestIds.next();
  private final Long otherAdminId = TestIds.next();
  private final Long guestUserId = TestIds.next();

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
    insertUser(adminId, "ADMIN", "00000001", "creador@example.com", false);
    insertUser(otherAdminId, "ADMIN", "00000003", "otra@example.com", false);
    insertUser(guestUserId, "GUEST", "00000002", "huesped@example.com", false);
    when(mail.sendAccessLinkNow(anyString(), anyString(), any())).thenReturn(true);
  }

  @AfterEach
  void tearDown() {
    TestTables.clear(jdbc);
  }

  private static ResultMatcher satisfiesTheContract() {
    return openApi().isValid(SPEC);
  }

  /** For a request that is invalid on purpose: only that complaint of the request is ignored. */
  private static ResultMatcher answersTheContractToARequestWith(String requestErrorKey) {
    var validator =
        OpenApiInteractionValidator.createFor(SPEC)
            .withWhitelist(
                ValidationErrorsWhitelist.create()
                    .withRule(
                        "the request is invalid on purpose",
                        WhitelistRules.allOf(
                            WhitelistRules.isRequest(),
                            WhitelistRules.messageHasKey(requestErrorKey))))
            .build();
    return openApi().isValid(validator);
  }

  private static String body(String type, String document, String email, String password) {
    return "{\"documentType\":\"%s\",\"documentNumber\":\"%s\",\"email\":\"%s\",\"currentPassword\":\"%s\"}"
        .formatted(type, document, email, password);
  }

  private static String validBody() {
    return body("DNI", NEW_DOCUMENT, NEW_EMAIL, PASSWORD);
  }

  private ResultActions create(String body) throws Exception {
    return create(session(adminId), body);
  }

  private ResultActions create(@Nullable Cookie session, String body) throws Exception {
    var request = post(URL).contentType(MediaType.APPLICATION_JSON).content(body);
    if (session != null) {
      request.cookie(session);
    }
    return mockMvc.perform(request);
  }

  @Test
  void createsTheAdministratorWhoChoosesItsPasswordWithTheLinkAndLogsIn() throws Exception {
    var created =
        create(validBody())
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.emailSent").value(true))
            .andExpect(jsonPath("$.currentPassword").doesNotExist())
            .andExpect(jsonPath("$.password").doesNotExist())
            .andExpect(satisfiesTheContract());
    String masked =
        JsonPath.read(created.andReturn().getResponse().getContentAsString(), "$.id").toString();

    Long id = ids.unmask(IdKind.USER, UUID.fromString(masked));
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT role, status, must_change_password, created_by_id, document_type,"
                + " document_number, email FROM users WHERE id = ?",
            id);
    assertThat(row)
        .containsEntry("role", "ADMIN")
        .containsEntry("status", "ACTIVE")
        .containsEntry("must_change_password", true)
        .containsEntry("created_by_id", adminId)
        .containsEntry("document_number", NEW_DOCUMENT)
        .containsEntry("email", NEW_EMAIL);

    var code = ArgumentCaptor.forClass(String.class);
    verify(mail).sendAccessLinkNow(eq(NEW_EMAIL), code.capture(), eq(Duration.ofMinutes(30)));
    assertThat(
            jdbc.queryForObject(
                "SELECT reset_token_hash FROM users WHERE id = ?", String.class, id))
        .hasSize(64)
        .doesNotContain(code.getValue());
    verify(notices).administratorCreated("otra@example.com", "creador@example.com", NEW_EMAIL);
    verify(notices, never())
        .administratorCreated(eq("creador@example.com"), anyString(), anyString());

    // Before choosing a password nothing opens the account.
    login(NEW_DOCUMENT, PASSWORD).andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            post("/bff/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"code\":\"%s\",\"newPassword\":\"Nueva12345\"}".formatted(code.getValue())))
        .andExpect(status().isNoContent());
    login(NEW_DOCUMENT, "Nueva12345")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.role").value("ADMIN"))
        .andExpect(jsonPath("$.mustChangePassword").value(false));
  }

  @Test
  void anEmailThatDidNotLeaveIsEmailSentFalseAndTheAccountExists() throws Exception {
    when(mail.sendAccessLinkNow(anyString(), anyString(), any())).thenReturn(false);

    create(validBody())
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.emailSent").value(false))
        .andExpect(satisfiesTheContract());

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE document_number = ?",
                Integer.class,
                NEW_DOCUMENT))
        .isEqualTo(1);
  }

  @Test
  void eachInvalidFieldIsAValidationError() throws Exception {
    create("{\"documentType\":\"DNI\",\"documentNumber\":\"22222222\",\"email\":\"a@example.com\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.field").value("currentPassword"))
        .andExpect(answersTheContractToARequestWith("validation.request.body.schema.required"));
    create(body("DNI", NEW_DOCUMENT, NEW_EMAIL, ""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("currentPassword"))
        .andExpect(answersTheContractToARequestWith("validation.request.body.schema.minLength"));
    create(body("DNI", NEW_DOCUMENT, NEW_EMAIL, "x".repeat(129)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("currentPassword"))
        .andExpect(answersTheContractToARequestWith("validation.request.body.schema.maxLength"));
    create(body("DNI", "", NEW_EMAIL, PASSWORD))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("documentNumber"))
        .andExpect(answersTheContractToARequestWith("validation.request.body.schema.minLength"));
    create(body("DNI", "1".repeat(21), NEW_EMAIL, PASSWORD))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("documentNumber"))
        .andExpect(answersTheContractToARequestWith("validation.request.body.schema.maxLength"));
    create(body("DNI", NEW_DOCUMENT, "no-es-un-correo", PASSWORD))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("email"))
        .andExpect(answersTheContractToARequestWith("validation.request.body.schema.format.email"));
    create(
            body(
                "DNI",
                NEW_DOCUMENT,
                "a@" + ("b".repeat(62) + ".").repeat(3) + "example.com",
                PASSWORD))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("email"))
        .andExpect(answersTheContractToARequestWith("validation.request.body.schema.maxLength"));
    create(body("LICENCIA", NEW_DOCUMENT, NEW_EMAIL, PASSWORD))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(answersTheContractToARequestWith("validation.request.body.schema.enum"));
    // Valid for the schema, but a DNI has 8 digits.
    create(body("DNI", "1234567A", NEW_EMAIL, PASSWORD))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.field").value("documentNumber"))
        .andExpect(satisfiesTheContract());

    assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(3);
    verifyNoInteractions(mail, notices);
  }

  @Test
  void withoutSessionIs401AndAGuestIs403() throws Exception {
    create(null, validBody())
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_NO_SESSION"))
        .andExpect(answersTheContractToARequestWith("validation.request.security.missing"));
    create(session(guestUserId), validBody())
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"))
        .andExpect(satisfiesTheContract());

    assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(3);
  }

  @Test
  void anAdministratorPendingItsPasswordChangeIs403() throws Exception {
    Long pending = TestIds.next();
    insertUser(pending, "ADMIN", "00000004", "pendiente@example.com", true);

    create(session(pending), validBody())
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_PASSWORD_CHANGE_REQUIRED"))
        .andExpect(satisfiesTheContract());

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE document_number = ?",
                Integer.class,
                NEW_DOCUMENT))
        .isZero();
  }

  @Test
  void aWrongCurrentPasswordIs403AndCountsAsAFailedLogin() throws Exception {
    create(body("DNI", NEW_DOCUMENT, NEW_EMAIL, "Incorrecta123"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"))
        .andExpect(jsonPath("$.field").value("currentPassword"))
        .andExpect(satisfiesTheContract());

    assertThat(
            jdbc.queryForObject(
                "SELECT failed_login_attempts FROM users WHERE id = ?", Integer.class, adminId))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE document_number = ?",
                Integer.class,
                NEW_DOCUMENT))
        .isZero();
    verifyNoInteractions(mail, notices);
  }

  @Test
  void aDocumentOfAGuestAccountIsTaken() throws Exception {
    create(body("DNI", "00000002", NEW_EMAIL, PASSWORD))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ACCOUNT_DOCUMENT_TAKEN"))
        .andExpect(jsonPath("$.field").value("documentNumber"))
        .andExpect(satisfiesTheContract());
  }

  @Test
  void anEmailIsTakenIgnoringCaseButAGuestContactEmailIsNot() throws Exception {
    create(body("DNI", NEW_DOCUMENT, "OTRA@Example.com", PASSWORD))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ACCOUNT_EMAIL_TAKEN"))
        .andExpect(jsonPath("$.field").value("email"))
        .andExpect(satisfiesTheContract());

    jdbc.update(
        "INSERT INTO guests (id, document_type, document_number, guest_type, status, email)"
            + " VALUES (?, 'DNI', '40000001', 'CONTRACT', 'ACTIVE', ?)",
        TestIds.next(),
        NEW_EMAIL);
    create(validBody()).andExpect(status().isCreated()).andExpect(satisfiesTheContract());
  }

  @Test
  void theSixthAdministratorIsRefused() throws Exception {
    insertUser(TestIds.next(), "ADMIN", "00000005", "c@example.com", false);
    insertUser(TestIds.next(), "ADMIN", "00000006", "d@example.com", false);
    create(validBody()).andExpect(status().isCreated());

    create(body("DNI", "33333333", "sexta@example.com", PASSWORD))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ADMIN_LIMIT_REACHED"))
        .andExpect(satisfiesTheContract());

    assertThat(
            jdbc.queryForObject("SELECT count(*) FROM users WHERE role = 'ADMIN'", Integer.class))
        .isEqualTo(5);
  }

  private ResultActions login(String document, String password) throws Exception {
    return mockMvc.perform(
        post("/bff/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"documentType\":\"DNI\",\"documentNumber\":\"%s\",\"password\":\"%s\"}"
                    .formatted(document, password)));
  }

  private Cookie session(Long userId) {
    return new Cookie("sessionToken", jwtTokenProvider.issue(userId.toString()));
  }

  private void insertUser(
      Long id, String role, String document, String email, boolean mustChangePassword) {
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " must_change_password, status) VALUES (?, ?, 'DNI', ?, ?, ?, ?, 'ACTIVE')",
        id,
        email,
        document,
        passwordEncoder.encode(PASSWORD),
        role,
        mustChangePassword);
  }
}
