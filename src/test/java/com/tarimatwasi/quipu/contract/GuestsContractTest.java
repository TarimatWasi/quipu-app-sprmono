package com.tarimatwasi.quipu.contract;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.whitelist.ValidationErrorsWhitelist;
import com.atlassian.oai.validator.whitelist.rule.WhitelistRules;
import com.tarimatwasi.quipu.auth.infrastructure.adapter.out.security.JwtTokenProvider;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.shared.masking.MaskSession;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.SessionIds;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;

/**
 * TAR-34: the real answers of the guest operations (RF-02) satisfy the shared contract, the one of
 * the tag pinned in pom.xml (contract.version).
 */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@AutoConfigureMockMvc
@ImportTestcontainers(PostgresContainers.class)
class GuestsContractTest {

  private static final String SPEC =
      java.nio.file.Path.of("target/contract/bff.yaml").toAbsolutePath().toString();
  private static final String URL = "/bff/admin/guests";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired JwtTokenProvider jwtTokenProvider;

  @Autowired IdMasker masker;

  /**
   * TAR-227: every account of a test signs in to this one session, so the ids of its requests and
   * of its checks are masked alike.
   */
  private final String sessionId = SessionIds.newSessionId();

  private final Long adminId = TestIds.next();
  private final Long guestAccountId = TestIds.next();

  /** The container is shared by all integration tests: this test owns users and guests. */
  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
    insertAccount(adminId, "ADMIN", "00000001", null);
    insertAccount(guestAccountId, "GUEST", "00000002", null);
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

  @Test
  void theListAnswersTheContractWithTheCurrentGuestsByDefault() throws Exception {
    insertGuest("12345678", "DNI", "CONTRACT", "PENDING_ACTIVATION", null);
    insertGuest("23456789", "DNI", "TEMPORARY", "ONBOARDING", "Ana Quispe");
    insertGuest("34567890", "DNI", "CONTRACT", "INACTIVE", "Luis Mamani");

    mockMvc
        .perform(get(URL).cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.content.length()").value(2))
        .andExpect(jsonPath("$.content[0].documentNumber").value("12345678"))
        .andExpect(jsonPath("$.content[0].name").doesNotExist())
        .andExpect(jsonPath("$.content[0].hasLoginAccess").value(false));
    mockMvc
        .perform(get(URL).param("status", "ALL").cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.content.length()").value(3));
    mockMvc
        .perform(get(URL).param("status", "INACTIVE").param("type", "CONTRACT").cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.content.length()").value(1));
  }

  /** A3 (TAR-237): the notices count every guest, whatever the filters of the page. */
  @Test
  void theListCountsThePendingAndOnboardingGuestsAcrossTheFilters() throws Exception {
    insertGuest("11111111", "DNI", "CONTRACT", "PENDING_ACTIVATION", null);
    insertGuest("22222222", "DNI", "CONTRACT", "PENDING_ACTIVATION", null);
    insertGuest("33333333", "DNI", "TEMPORARY", "ONBOARDING", null);
    insertGuest("44444444", "DNI", "CONTRACT", "INACTIVE", "Eva Paz");

    mockMvc
        .perform(get(URL).param("status", "INACTIVE").cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.pendingActivationCount").value(2))
        .andExpect(jsonPath("$.onboardingCount").value(1));
  }

  /** A3 (TAR-237): each row names the environment it occupies and, if pending, its access. */
  @Test
  void eachRowShowsItsEnvironmentAndThePendingOneWhenItsAccessExpires() throws Exception {
    var today = java.time.LocalDate.now(java.time.ZoneId.of("America/Lima"));
    var withContract = insertGuest("11111111", "DNI", "CONTRACT", "ACTIVE", "Ana Quispe");
    var withStay = insertGuest("22222222", "DNI", "TEMPORARY", "ACTIVE", "Luis Mamani");
    var pending = insertGuest("33333333", "DNI", "CONTRACT", "PENDING_ACTIVATION", null);
    var room = insertEnvironment("201", "ROOM");
    var cabin = insertEnvironment("C1", "CABIN");
    insertContract(withContract, room, today.minusDays(10), today.plusDays(300));
    insertStay(withStay, cabin, today.plusDays(30), today.plusDays(35));
    jdbc.update(
        "INSERT INTO access_grants (id, guest_id, mode, token_hash, expires_at)"
            + " VALUES (?, ?, 'LINK', ?, now() - interval '1 hour')",
        TestIds.next(),
        pending,
        "a".repeat(64));

    mockMvc
        .perform(get(URL).cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.content[0].environment.code").value("201"))
        .andExpect(jsonPath("$.content[0].environment.type").value("ROOM"))
        .andExpect(jsonPath("$.content[0].environment.kind").value("CONTRACT"))
        .andExpect(
            jsonPath("$.content[0].environment.endDate").value(today.plusDays(300).toString()))
        .andExpect(jsonPath("$.content[0].accessExpiresAt").value((Object) null))
        .andExpect(jsonPath("$.content[1].environment.code").value("C1"))
        .andExpect(jsonPath("$.content[1].environment.kind").value("STAY"))
        .andExpect(
            jsonPath("$.content[1].environment.startDate").value(today.plusDays(30).toString()))
        .andExpect(jsonPath("$.content[2].environment").value((Object) null))
        .andExpect(jsonPath("$.content[2].accessExpiresAt").isString());
  }

  /** A4.1 (TAR-237): to load a contract only the guests without one in force are offered. */
  @Test
  void withoutContractLeavesOutTheGuestsWithAContractInForce() throws Exception {
    var today = java.time.LocalDate.now(java.time.ZoneId.of("America/Lima"));
    var withContract = insertGuest("11111111", "DNI", "CONTRACT", "ACTIVE", "Ana Quispe");
    insertGuest("22222222", "DNI", "CONTRACT", "ACTIVE", "Luis Mamani");
    insertGuest("33333333", "DNI", "CONTRACT", "PENDING_ACTIVATION", null);
    insertContract(
        withContract, insertEnvironment("201", "ROOM"), today.minusDays(10), today.plusDays(300));

    var first =
        mockMvc
            .perform(
                get(URL)
                    .param("type", "CONTRACT")
                    .param("withoutContract", "true")
                    .param("limit", "1")
                    .cookie(admin()))
            .andExpect(status().isOk())
            .andExpect(satisfiesTheContract())
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].documentNumber").value("22222222"))
            .andReturn();
    String cursor =
        com.jayway.jsonpath.JsonPath.read(first.getResponse().getContentAsString(), "$.nextCursor");
    mockMvc
        .perform(
            get(URL)
                .param("type", "CONTRACT")
                .param("withoutContract", "true")
                .param("limit", "1")
                .param("after", cursor)
                .cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.content[0].documentNumber").value("33333333"))
        .andExpect(jsonPath("$.nextCursor").value((Object) null));
  }

  @Test
  void aGuestWithAnAccountThatChoseItsPasswordHasLoginAccess() throws Exception {
    var id = insertGuest("12345678", "DNI", "CONTRACT", "ACTIVE", "Ana Quispe");
    insertAccount(TestIds.next(), "GUEST", "12345678", id);

    mockMvc
        .perform(get(URL).cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.content[0].hasLoginAccess").value(true));
  }

  @Test
  void theDetailOfEveryStateAnswersTheContract() throws Exception {
    var pending = insertGuest("11111111", "DNI", "CONTRACT", "PENDING_ACTIVATION", null);
    var onboarding = insertGuest("22222222", "DNI", "CONTRACT", "ONBOARDING", null);
    var saved = insertGuest("33333333", "DNI", "CONTRACT", "ONBOARDING", "Ana Quispe");
    jdbc.update(
        "UPDATE guests SET phone = '999111222', personal_data_completed_at = now() WHERE id = ?",
        saved);
    var active = insertGuest("44444444", "DNI", "TEMPORARY", "ACTIVE", "Luis Mamani");
    jdbc.update(
        "UPDATE guests SET phone = '999333444',"
            + " emergency_contact_name = 'Rosa', emergency_contact_relationship = 'Madre',"
            + " emergency_contact_phone = '999555666' WHERE id = ?",
        active);
    jdbc.update(
        "INSERT INTO stays (id, guest_id, start_date, end_date, agreed_amount)"
            + " VALUES (?, ?, DATE '2026-10-10', DATE '2026-10-20', 150.50)",
        TestIds.next(),
        active);
    var inactive = insertGuest("55555555", "DNI", "CONTRACT", "INACTIVE", "Eva Paz");
    jdbc.update(
        "UPDATE guests SET phone = '999777888', status_before_inactive = 'ACTIVE' WHERE id = ?",
        inactive);

    for (var id : new Long[] {pending, onboarding, saved, active, inactive}) {
      mockMvc
          .perform(get(URL + "/" + masked(id)).cookie(admin()))
          .andExpect(status().isOk())
          .andExpect(satisfiesTheContract());
    }
    mockMvc
        .perform(get(URL + "/" + masked(pending)).cookie(admin()))
        .andExpect(jsonPath("$.version").value(versionOf(pending)))
        .andExpect(jsonPath("$.accountEmail").value((Object) null))
        .andExpect(jsonPath("$.onboardingProgress").value("NOT_STARTED"))
        .andExpect(jsonPath("$.documentCount").value(0))
        .andExpect(jsonPath("$.paymentHistory.length()").value(0));
    mockMvc
        .perform(get(URL + "/" + masked(saved)).cookie(admin()))
        .andExpect(jsonPath("$.onboardingProgress").value("PERSONAL_DATA_SAVED"));
    mockMvc
        .perform(get(URL + "/" + masked(active)).cookie(admin()))
        .andExpect(jsonPath("$.onboardingProgress").value("COMPLETED"))
        .andExpect(jsonPath("$.emergencyContact.name").value("Rosa"))
        .andExpect(jsonPath("$.agreedAmount").value(150.50));
    mockMvc
        .perform(get(URL + "/" + masked(inactive)).cookie(admin()))
        .andExpect(jsonPath("$.onboardingProgress").value("COMPLETED"));
  }

  @Test
  void theDocumentIsCorrectedWhileTheGuestIsPendingActivation() throws Exception {
    var id = insertGuest("12345678", "DNI", "CONTRACT", "PENDING_ACTIVATION", null);

    patchGuest(id, "{\"documentNumber\":\"87654321\"}")
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.documentNumber").value("87654321"));
    patchGuest(id, "{\"documentType\":\"PASSPORT\",\"documentNumber\":\"ab123456\"}")
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.documentType").value("PASSPORT"))
        .andExpect(jsonPath("$.documentNumber").value("AB123456"));
  }

  /** TAR-276: an edit from a version the guest no longer has saves nothing. */
  @Test
  void anEditFromAnOldVersionIsAConcurrentModification() throws Exception {
    var id = insertGuest("12345678", "DNI", "CONTRACT", "PENDING_ACTIVATION", null);
    long loaded = versionOf(id);
    patchGuest(id, "{\"documentNumber\":\"87654321\"}").andExpect(status().isOk());

    mockMvc
        .perform(
            patch(URL + "/" + masked(id))
                .cookie(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + loaded + ",\"documentNumber\":\"11112222\"}"))
        .andExpect(status().isConflict())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    org.assertj.core.api.Assertions.assertThat(
            jdbc.queryForObject(
                "SELECT document_number FROM guests WHERE id = ?", String.class, id))
        .isEqualTo("87654321");
  }

  @Test
  void theDocumentOfAnOnboardingGuestIsLocked() throws Exception {
    var id = insertGuest("12345678", "DNI", "CONTRACT", "ONBOARDING", null);

    patchGuest(id, "{\"documentNumber\":\"87654321\"}")
        .andExpect(status().isConflict())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("GUEST_DOCUMENT_LOCKED"))
        .andExpect(jsonPath("$.field").value("documentNumber"));
  }

  @Test
  void aDocumentOfAnotherGuestIsTaken() throws Exception {
    insertGuest("87654321", "DNI", "CONTRACT", "ACTIVE", "Ana Quispe");
    var id = insertGuest("12345678", "DNI", "CONTRACT", "PENDING_ACTIVATION", null);

    patchGuest(id, "{\"documentNumber\":\"87654321\"}")
        .andExpect(status().isConflict())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("GUEST_DOCUMENT_TAKEN"));
  }

  @Test
  void aDocumentOfAnAdministratorIsTaken() throws Exception {
    var id = insertGuest("12345678", "DNI", "CONTRACT", "PENDING_ACTIVATION", null);

    patchGuest(id, "{\"documentNumber\":\"00000001\"}")
        .andExpect(status().isConflict())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("GUEST_DOCUMENT_TAKEN"))
        .andExpect(jsonPath("$.field").value("documentNumber"));
  }

  /** TAR-182: the stays have their own endpoints; the guest edit no longer takes stay fields. */
  @Test
  void theStayFieldsAreNoLongerPartOfTheGuestEdit() throws Exception {
    var id = insertGuest("12345678", "DNI", "TEMPORARY", "ACTIVE", "Ana Quispe");

    for (var body :
        new String[] {
          "{\"stayStartDate\":\"2026-10-10\"}",
          "{\"agreedAmount\":300.50}",
          "{\"documentNumber\":\"87654321\",\"stayEndDate\":\"2026-10-20\"}"
        }) {
      patchGuest(id, body)
          .andExpect(status().isBadRequest())
          .andExpect(
              answersTheContractToARequestWith(
                  "validation.request.body.schema.additionalProperties"));
    }
    var stays = jdbc.queryForObject("SELECT count(*) FROM stays", Integer.class);
    org.assertj.core.api.Assertions.assertThat(stays).isZero();
  }

  @Test
  void invalidEditsAreValidationErrors() throws Exception {
    var contract = insertGuest("12345678", "DNI", "CONTRACT", "PENDING_ACTIVATION", null);

    patchGuest(contract, "{\"documentNumber\":\"123\"}")
        .andExpect(status().isBadRequest())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.field").value("documentNumber"));
    patchGuest(contract, "{}") // only the version
        .andExpect(status().isBadRequest())
        .andExpect(
            answersTheContractToARequestWith("validation.request.body.schema.minProperties"));
    patchGuest(contract, "{\"documentType\":\"LICENSE\"}")
        .andExpect(status().isBadRequest())
        .andExpect(answersTheContractToARequestWith("validation.request.body.schema.enum"));
  }

  @Test
  void anUnknownGuestIsNotFound() throws Exception {
    mockMvc
        .perform(get(URL + "/" + UUID.randomUUID()).cookie(admin()))
        .andExpect(status().isNotFound())
        .andExpect(satisfiesTheContract());
    patchGuest(999_999L, "{\"documentNumber\":\"87654321\"}")
        .andExpect(status().isNotFound())
        .andExpect(satisfiesTheContract());
  }

  @Test
  void withoutSessionTheAnswerIs401AndAGuestSessionIs403() throws Exception {
    mockMvc
        .perform(get(URL))
        .andExpect(status().isUnauthorized())
        .andExpect(answersTheContractToARequestWith("validation.request.security.missing"));
    mockMvc
        .perform(
            get(URL)
                .cookie(
                    new Cookie(
                        "sessionToken",
                        jwtTokenProvider.renew(guestAccountId.toString(), sessionId))))
        .andExpect(status().isForbidden())
        .andExpect(satisfiesTheContract());
  }

  private UUID masked(Long id) {
    return sessionIds().mask(IdKind.GUEST, id);
  }

  private Cookie admin() {
    return new Cookie("sessionToken", jwtTokenProvider.renew(adminId.toString(), sessionId));
  }

  /** Edits from the version the guest has now, as a client that just loaded it (TAR-276). */
  private ResultActions patchGuest(Long id, String body) throws Exception {
    String rest = body.substring(1).strip();
    String versioned = "{\"version\":" + versionOf(id) + (rest.equals("}") ? "" : ",") + rest;
    return mockMvc.perform(
        patch(URL + "/" + masked(id))
            .cookie(admin())
            .contentType(MediaType.APPLICATION_JSON)
            .content(versioned));
  }

  /** The version the guest has now; 0 for one that does not exist. */
  private long versionOf(Long id) {
    return jdbc.queryForList("SELECT version FROM guests WHERE id = ?", Long.class, id).stream()
        .findFirst()
        .orElse(0L);
  }

  private Long insertEnvironment(String code, String type) {
    Long id = TestIds.next();
    jdbc.update(
        "INSERT INTO environments (id, code, type, status) VALUES (?, ?, ?, 'ACTIVE')",
        id,
        code,
        type);
    return id;
  }

  private void insertContract(
      Long guest, Long environment, java.time.LocalDate start, java.time.LocalDate end) {
    jdbc.update(
        "INSERT INTO contracts (id, guest_id, environment_id, start_date, end_date, monthly_amount,"
            + " payment_day, grace_days, file_key, original_name, size_bytes, status)"
            + " VALUES (?, ?, ?, ?, ?, 800, 5, 3, 'contracts/seed.pdf', 'seed.pdf', 10, 'ACTIVE')",
        TestIds.next(),
        guest,
        environment,
        start,
        end);
  }

  private void insertStay(
      Long guest, Long environment, java.time.LocalDate start, java.time.LocalDate end) {
    jdbc.update(
        "INSERT INTO stays (id, guest_id, environment_id, start_date, end_date, agreed_amount)"
            + " VALUES (?, ?, ?, ?, ?, 100)",
        TestIds.next(),
        guest,
        environment,
        start,
        end);
  }

  private Long insertGuest(
      String number, String documentType, String type, String status, String name) {
    Long id = TestIds.next();
    jdbc.update(
        "INSERT INTO guests (id, full_name, document_type, document_number, guest_type, status)"
            + " VALUES (?, ?, ?, ?, ?, ?)",
        id,
        name,
        documentType,
        number,
        type,
        status);
    return id;
  }

  private void insertAccount(Long id, String role, String document, Long guestId) {
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " guest_id, must_change_password, status) VALUES (?, ?, 'DNI', ?, 'hash', ?, ?,"
            + " FALSE, 'ACTIVE')",
        id,
        document + "@example.test",
        document,
        role,
        guestId);
  }

  /** The ids as the session of the test shows them. */
  private MaskSession sessionIds() {
    return masker.session(sessionId);
  }
}
