package com.tarimatwasi.quipu.contract;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.whitelist.ValidationErrorsWhitelist;
import com.atlassian.oai.validator.whitelist.rule.WhitelistRules;
import com.tarimatwasi.quipu.auth.application.port.out.AdministratorNoticePort;
import com.tarimatwasi.quipu.auth.application.port.out.AdministratorNoticePort.StatusChange;
import com.tarimatwasi.quipu.auth.application.port.out.AdministratorStorePort;
import com.tarimatwasi.quipu.auth.application.port.out.PasswordResetMailPort;
import com.tarimatwasi.quipu.auth.infrastructure.adapter.out.security.JwtTokenProvider;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.shared.masking.MaskSession;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.SessionIds;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import jakarta.servlet.http.Cookie;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * TAR-127 (RF-22): the real answers of listing, deactivating and reactivating administrators
 * satisfy the shared contract, the one of the tag pinned in pom.xml (contract.version), with the
 * exact bodies, and each operation leaves the database as it says.
 */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@AutoConfigureMockMvc
@ImportTestcontainers(PostgresContainers.class)
class AdministratorManagementContractTest {

  private static final String SPEC =
      Path.of("target/contract/bff.yaml").toAbsolutePath().toString();
  private static final String URL = "/bff/admin/administrators";
  private static final String PASSWORD = "Actor12345";
  private static final String BODY = "{\"currentPassword\":\"" + PASSWORD + "\"}";
  private static final String ACTOR_EMAIL = "actor@example.com";
  private static final String TARGET_EMAIL = "destino@example.com";
  private static final String THIRD_EMAIL = "tercera@example.com";

  private static final String NOT_FOUND =
      "{\"code\":\"ADMIN_NOT_FOUND\",\"message\":\"El administrador no existe\"}";
  private static final String WRONG_PASSWORD =
      "{\"code\":\"AUTH_INVALID_CREDENTIALS\",\"message\":\"Tu contraseña actual no es correcta\","
          + "\"field\":\"currentPassword\"}";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired JwtTokenProvider jwtTokenProvider;

  /**
   * TAR-227: every account of a test signs in to this one session, so an id one request shows is
   * valid in the next.
   */
  private final String sessionId = SessionIds.newSessionId();

  @Autowired IdMasker ids;
  @Autowired PasswordEncoder passwordEncoder;
  @MockitoBean AdministratorNoticePort notices;
  @MockitoSpyBean AdministratorStorePort store;

  /** Unused here: the overrides of AdministratorsContractTest, to share its Spring context. */
  @MockitoBean PasswordResetMailPort mail;

  private final Long actorId = TestIds.next();
  private final Long targetId = TestIds.next();
  private final Long thirdId = TestIds.next();
  private final Long guestUserId = TestIds.next();

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
    String hash = passwordEncoder.encode(PASSWORD);
    insertUser(actorId, "ADMIN", "DNI", "12345678", ACTOR_EMAIL, false, hash);
    insertUser(targetId, "ADMIN", "CE", "AB4321", TARGET_EMAIL, false, hash);
    insertUser(thirdId, "ADMIN", "DNI", "11111113", THIRD_EMAIL, false, hash);
    insertUser(guestUserId, "GUEST", "DNI", "00000002", "huesped@example.com", false, hash);
    setCreated(actorId, "2026-01-15T14:00:00Z");
    setCreated(targetId, "2026-03-02T16:30:00Z");
    setCreated(thirdId, "2026-02-01T09:00:00Z");
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

  private static ResultMatcher exactly(String json) {
    return content().json(json, JsonCompareMode.STRICT);
  }

  @Test
  void listsEveryAdministratorTheOldestFirstWithTheDocumentMaskedAndSelfWithoutCache()
      throws Exception {
    jdbc.update("UPDATE users SET status = 'INACTIVE' WHERE id = ?", targetId);

    list(session(actorId))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(
            exactly(
                """
                [
                  {"id":"%s","documentType":"DNI","documentNumber":"*****678",
                   "email":"actor@example.com","status":"ACTIVE",
                   "createdAt":"2026-01-15T14:00:00Z","self":true},
                  {"id":"%s","documentType":"DNI","documentNumber":"*****113",
                   "email":"tercera@example.com","status":"ACTIVE",
                   "createdAt":"2026-02-01T09:00:00Z","self":false},
                  {"id":"%s","documentType":"CE","documentNumber":"***321",
                   "email":"destino@example.com","status":"INACTIVE",
                   "createdAt":"2026-03-02T16:30:00Z","self":false}
                ]
                """
                    .formatted(masked(actorId), masked(thirdId), masked(targetId))))
        .andExpect(satisfiesTheContract());
  }

  @Test
  void deactivatesAnotherAdministratorWhoseNextRequestHasNoSessionAndWhoCannotLogIn()
      throws Exception {
    Cookie targetSession = session(targetId);
    list(targetSession).andExpect(status().isOk());

    deactivate(targetId, BODY)
        .andExpect(status().isNoContent())
        .andExpect(content().string(""))
        .andExpect(satisfiesTheContract());

    assertThat(row(targetId))
        .containsEntry("status", "INACTIVE")
        .containsEntry("last_modified_by_id", actorId)
        .containsEntry("role", "ADMIN");
    assertThat(statusOf(actorId)).isEqualTo("ACTIVE");
    assertThat(statusOf(thirdId)).isEqualTo("ACTIVE");
    verify(notices)
        .administratorStatusChanged(
            THIRD_EMAIL, ACTOR_EMAIL, TARGET_EMAIL, StatusChange.DEACTIVATED);
    verifyNoMoreInteractions(notices);

    // Effective immediately: the session it already had stops working, and so does the login.
    list(targetSession)
        .andExpect(status().isUnauthorized())
        .andExpect(
            exactly(
                "{\"code\":\"AUTH_NO_SESSION\",\"message\":\"Tu sesión no es válida o expiró\"}"));
    login("CE", "AB4321")
        .andExpect(status().isForbidden())
        .andExpect(
            exactly("{\"code\":\"AUTH_ACCOUNT_DISABLED\",\"message\":\"Cuenta deshabilitada\"}"));
  }

  @Test
  void deactivatingAnInactiveAdministratorIs204AndChangesNothing() throws Exception {
    jdbc.update("UPDATE users SET status = 'INACTIVE' WHERE id = ?", targetId);

    deactivate(targetId, BODY).andExpect(status().isNoContent()).andExpect(satisfiesTheContract());

    assertThat(row(targetId))
        .containsEntry("status", "INACTIVE")
        .containsEntry("last_modified_by_id", null);
    verifyNoInteractions(notices);
  }

  @Test
  void reactivatesAnInactiveAdministratorWhoCanLogInAgain() throws Exception {
    jdbc.update("UPDATE users SET status = 'INACTIVE' WHERE id = ?", targetId);

    reactivate(targetId, BODY)
        .andExpect(status().isNoContent())
        .andExpect(content().string(""))
        .andExpect(satisfiesTheContract());

    assertThat(row(targetId))
        .containsEntry("status", "ACTIVE")
        .containsEntry("last_modified_by_id", actorId);
    verify(notices)
        .administratorStatusChanged(
            THIRD_EMAIL, ACTOR_EMAIL, TARGET_EMAIL, StatusChange.REACTIVATED);
    verifyNoMoreInteractions(notices);
    login("CE", "AB4321").andExpect(status().isOk());
    list(session(targetId)).andExpect(status().isOk());
  }

  @Test
  void reactivatingAnActiveAdministratorIs204AndChangesNothing() throws Exception {
    reactivate(targetId, BODY).andExpect(status().isNoContent()).andExpect(satisfiesTheContract());

    assertThat(row(targetId))
        .containsEntry("status", "ACTIVE")
        .containsEntry("last_modified_by_id", null);
    verifyNoInteractions(notices);
  }

  @Test
  void anAdministratorCannotDeactivateTheirOwnAccount() throws Exception {
    deactivate(actorId, BODY)
        .andExpect(status().isConflict())
        .andExpect(
            exactly(
                "{\"code\":\"ADMIN_SELF_DEACTIVATION\","
                    + "\"message\":\"No puedes desactivar tu propia cuenta\"}"))
        .andExpect(satisfiesTheContract());

    assertThat(statusOf(actorId)).isEqualTo("ACTIVE");
    verifyNoInteractions(notices);
  }

  /** The count under the lock decides; here it is made to find the target as the last one. */
  @Test
  void theLastActiveAdministratorIsNeverDeactivated() throws Exception {
    doReturn(1).when(store).countActiveAdministrators();

    deactivate(targetId, BODY)
        .andExpect(status().isConflict())
        .andExpect(
            exactly(
                "{\"code\":\"LAST_ACTIVE_ADMIN\","
                    + "\"message\":\"No puedes desactivar al último administrador activo\"}"))
        .andExpect(satisfiesTheContract());

    assertThat(statusOf(targetId)).isEqualTo("ACTIVE");
    verifyNoInteractions(notices);
  }

  @Test
  void anUnknownIdAForgedOneAndTheIdOfAGuestAreAnAdministratorThatDoesNotExist() throws Exception {
    for (UUID id : new UUID[] {masked(TestIds.next()), UUID.randomUUID(), masked(guestUserId)}) {
      deactivate(id, BODY)
          .andExpect(status().isNotFound())
          .andExpect(exactly(NOT_FOUND))
          .andExpect(satisfiesTheContract());
      reactivate(id, BODY)
          .andExpect(status().isNotFound())
          .andExpect(exactly(NOT_FOUND))
          .andExpect(satisfiesTheContract());
    }

    assertThat(statusOf(guestUserId)).isEqualTo("ACTIVE");
    assertThat(failedLogins(actorId)).isZero();
    verifyNoInteractions(notices);
  }

  /** The password is checked before the id is looked up: a wrong one tells nothing about the id. */
  @Test
  void aWrongPasswordIs403BeforeTheIdIsLookedUpAndCountsAsAFailedLogin() throws Exception {
    String wrong = "{\"currentPassword\":\"Incorrecta123\"}";
    deactivate(targetId, wrong)
        .andExpect(status().isForbidden())
        .andExpect(exactly(WRONG_PASSWORD))
        .andExpect(satisfiesTheContract());
    deactivate(masked(TestIds.next()), wrong)
        .andExpect(status().isForbidden())
        .andExpect(exactly(WRONG_PASSWORD));
    reactivate(UUID.randomUUID(), wrong)
        .andExpect(status().isForbidden())
        .andExpect(exactly(WRONG_PASSWORD))
        .andExpect(satisfiesTheContract());

    assertThat(failedLogins(actorId)).isEqualTo(3);
    assertThat(statusOf(targetId)).isEqualTo("ACTIVE");
    verifyNoInteractions(notices);
  }

  @Test
  void eachInvalidRequestIsAValidationError() throws Exception {
    deactivate(targetId, "{}")
        .andExpect(status().isBadRequest())
        .andExpect(
            exactly(
                "{\"code\":\"VALIDATION_ERROR\",\"message\":\"Datos de entrada inválidos\","
                    + "\"field\":\"currentPassword\"}"))
        .andExpect(answersTheContractToARequestWith("validation.request.body.schema.required"));
    reactivate(targetId, "{\"currentPassword\":\"\"}")
        .andExpect(status().isBadRequest())
        .andExpect(content().json("{\"code\":\"VALIDATION_ERROR\",\"field\":\"currentPassword\"}"))
        .andExpect(answersTheContractToARequestWith("validation.request.body.schema.minLength"));
    deactivate(targetId, "{\"currentPassword\":\"" + "x".repeat(129) + "\"}")
        .andExpect(status().isBadRequest())
        .andExpect(content().json("{\"code\":\"VALIDATION_ERROR\",\"field\":\"currentPassword\"}"))
        .andExpect(answersTheContractToARequestWith("validation.request.body.schema.maxLength"));
    perform(post(URL + "/no-es-un-uuid/deactivate"), session(actorId), BODY)
        .andExpect(status().isBadRequest())
        .andExpect(
            exactly(
                "{\"code\":\"VALIDATION_ERROR\",\"message\":\"Datos de entrada inválidos\","
                    + "\"field\":\"id\"}"));

    assertThat(statusOf(targetId)).isEqualTo("ACTIVE");
    assertThat(failedLogins(actorId)).isZero();
    verifyNoInteractions(notices);
  }

  @Test
  void withoutSessionIs401AndAGuestIs403() throws Exception {
    for (MockHttpServletRequestBuilder request : threeRoutes()) {
      perform(request, null)
          .andExpect(status().isUnauthorized())
          .andExpect(
              exactly(
                  "{\"code\":\"AUTH_NO_SESSION\",\"message\":\"Tu sesión no es válida o expiró\"}"))
          .andExpect(answersTheContractToARequestWith("validation.request.security.missing"));
    }
    for (MockHttpServletRequestBuilder request : threeRoutes()) {
      perform(request, session(guestUserId))
          .andExpect(status().isForbidden())
          .andExpect(
              exactly(
                  "{\"code\":\"AUTH_FORBIDDEN\",\"message\":\"No tienes permiso para esta acción\"}"))
          .andExpect(satisfiesTheContract());
    }

    assertThat(statusOf(targetId)).isEqualTo("ACTIVE");
    verifyNoInteractions(notices);
  }

  @Test
  void anAdministratorPendingItsPasswordChangeIs403() throws Exception {
    Long pending = TestIds.next();
    insertUser(
        pending,
        "ADMIN",
        "DNI",
        "00000004",
        "pendiente@example.com",
        true,
        passwordEncoder.encode(PASSWORD));

    String required =
        "{\"code\":\"AUTH_PASSWORD_CHANGE_REQUIRED\","
            + "\"message\":\"Debes cambiar tu contraseña para continuar\"}";
    for (MockHttpServletRequestBuilder request : threeRoutes()) {
      perform(request, session(pending))
          .andExpect(status().isForbidden())
          .andExpect(exactly(required))
          .andExpect(satisfiesTheContract());
    }

    assertThat(statusOf(targetId)).isEqualTo("ACTIVE");
    assertThat(failedLogins(pending)).isZero();
    verifyNoInteractions(notices);
  }

  private ResultActions list(Cookie session) throws Exception {
    return mockMvc.perform(get(URL).cookie(session));
  }

  private ResultActions deactivate(Long id, String body) throws Exception {
    return deactivate(masked(id), body);
  }

  private ResultActions deactivate(UUID id, String body) throws Exception {
    return perform(post(URL + "/" + id + "/deactivate"), session(actorId), body);
  }

  private ResultActions reactivate(Long id, String body) throws Exception {
    return reactivate(masked(id), body);
  }

  private ResultActions reactivate(UUID id, String body) throws Exception {
    return perform(post(URL + "/" + id + "/reactivate"), session(actorId), body);
  }

  private ResultActions perform(
      MockHttpServletRequestBuilder request, @Nullable Cookie session, String body)
      throws Exception {
    return perform(request.contentType(MediaType.APPLICATION_JSON).content(body), session);
  }

  private ResultActions perform(MockHttpServletRequestBuilder request, @Nullable Cookie session)
      throws Exception {
    if (session != null) {
      request.cookie(session);
    }
    return mockMvc.perform(request);
  }

  /** The list, and a deactivation and a reactivation with a valid body, as new requests. */
  private MockHttpServletRequestBuilder[] threeRoutes() {
    return new MockHttpServletRequestBuilder[] {
      get(URL),
      post(URL + "/" + masked(targetId) + "/deactivate")
          .contentType(MediaType.APPLICATION_JSON)
          .content(BODY),
      post(URL + "/" + masked(targetId) + "/reactivate")
          .contentType(MediaType.APPLICATION_JSON)
          .content(BODY)
    };
  }

  private ResultActions login(String type, String document) throws Exception {
    return mockMvc.perform(
        post("/bff/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"documentType\":\"%s\",\"documentNumber\":\"%s\",\"password\":\"%s\"}"
                    .formatted(type, document, PASSWORD)));
  }

  private UUID masked(Long id) {
    return sessionIds().mask(IdKind.USER, id);
  }

  private Cookie session(Long userId) {
    return new Cookie("sessionToken", jwtTokenProvider.renew(userId.toString(), sessionId));
  }

  private Map<String, @Nullable Object> row(Long id) {
    return jdbc.queryForMap("SELECT role, status, last_modified_by_id FROM users WHERE id = ?", id);
  }

  private String statusOf(Long id) {
    return jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, id);
  }

  private int failedLogins(Long id) {
    Integer failed =
        jdbc.queryForObject(
            "SELECT failed_login_attempts FROM users WHERE id = ?", Integer.class, id);
    return failed == null ? 0 : failed;
  }

  private void setCreated(Long id, String instant) {
    jdbc.update("UPDATE users SET created_date = ?::timestamptz WHERE id = ?", instant, id);
  }

  private void insertUser(
      Long id,
      String role,
      String type,
      String document,
      String email,
      boolean mustChangePassword,
      String hash) {
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " must_change_password, status) VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE')",
        id,
        email,
        type,
        document,
        hash,
        role,
        mustChangePassword);
  }

  /** The ids as the session of the test shows them. */
  private MaskSession sessionIds() {
    return ids.session(sessionId);
  }
}
