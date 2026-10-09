package com.tarimatwasi.quipu.contract;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.whitelist.ValidationErrorsWhitelist;
import com.atlassian.oai.validator.whitelist.rule.WhitelistRules;
import com.tarimatwasi.quipu.auth.adapter.out.security.JwtTokenProvider;
import com.tarimatwasi.quipu.bff.adapter.in.rest.IdKind;
import com.tarimatwasi.quipu.bff.adapter.in.rest.IdMasker;
import com.tarimatwasi.quipu.support.PostgresContainers;
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
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].documentNumber").value("12345678"))
        .andExpect(jsonPath("$[0].name").doesNotExist())
        .andExpect(jsonPath("$[0].hasLoginAccess").value(false));
    mockMvc
        .perform(get(URL).param("status", "ALL").cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.length()").value(3));
    mockMvc
        .perform(get(URL).param("status", "INACTIVE").param("type", "CONTRACT").cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.length()").value(1));
  }

  @Test
  void aGuestWithAnAccountThatChoseItsPasswordHasLoginAccess() throws Exception {
    var id = insertGuest("12345678", "DNI", "CONTRACT", "ACTIVE", "Ana Quispe");
    insertAccount(TestIds.next(), "GUEST", "12345678", id);

    mockMvc
        .perform(get(URL).cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$[0].hasLoginAccess").value(true));
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
        "UPDATE guests SET phone = '999333444', stay_start_date = '2026-10-10',"
            + " stay_end_date = '2026-10-20', agreed_amount = 150.50,"
            + " emergency_contact_name = 'Rosa', emergency_contact_relationship = 'Madre',"
            + " emergency_contact_phone = '999555666' WHERE id = ?",
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
  void theStayOfATemporaryGuestIsEdited() throws Exception {
    var id = insertGuest("12345678", "DNI", "TEMPORARY", "ACTIVE", "Ana Quispe");
    jdbc.update("UPDATE guests SET phone = '999111222' WHERE id = ?", id);

    patchGuest(
            id,
            "{\"stayStartDate\":\"2026-10-10\",\"stayEndDate\":\"2026-10-20\","
                + "\"agreedAmount\":300.50}")
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract());
    mockMvc
        .perform(get(URL + "/" + masked(id)).cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.stayEndDate").value("2026-10-20"))
        .andExpect(jsonPath("$.agreedAmount").value(300.50));
  }

  @Test
  void invalidEditsAreValidationErrors() throws Exception {
    var contract = insertGuest("12345678", "DNI", "CONTRACT", "PENDING_ACTIVATION", null);
    var temporary = insertGuest("23456789", "DNI", "TEMPORARY", "ACTIVE", "Ana Quispe");

    patchGuest(contract, "{\"documentNumber\":\"123\"}")
        .andExpect(status().isBadRequest())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.field").value("documentNumber"));
    patchGuest(contract, "{\"agreedAmount\":100}")
        .andExpect(status().isBadRequest())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.field").value("agreedAmount"));
    patchGuest(temporary, "{\"stayStartDate\":\"2026-10-20\",\"stayEndDate\":\"2026-10-10\"}")
        .andExpect(status().isBadRequest())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.field").value("stayEndDate"));
    patchGuest(contract, "{}").andExpect(status().isBadRequest()).andExpect(satisfiesTheContract());
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
                        jwtTokenProvider.issue(guestAccountId.toString(), "GUEST"))))
        .andExpect(status().isForbidden())
        .andExpect(satisfiesTheContract());
  }

  private UUID masked(Long id) {
    return masker.mask(IdKind.GUEST, id);
  }

  private Cookie admin() {
    return new Cookie("sessionToken", jwtTokenProvider.issue(adminId.toString(), "ADMIN"));
  }

  private ResultActions patchGuest(Long id, String body) throws Exception {
    return mockMvc.perform(
        patch(URL + "/" + masked(id))
            .cookie(admin())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
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
}
