package com.tarimatwasi.quipu.contract;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;

/**
 * TAR-182: the real answers of the stays of a guest (RN-13, RN-17) satisfy the shared contract, the
 * one of the tag pinned in pom.xml (contract.version).
 */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@AutoConfigureMockMvc
@ImportTestcontainers(PostgresContainers.class)
class StaysContractTest {

  private static final String SPEC =
      java.nio.file.Path.of("target/contract/bff.yaml").toAbsolutePath().toString();
  private static final String URL = "/bff/admin/guests";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired JwtTokenProvider jwtTokenProvider;
  @Autowired IdMasker masker;
  private @Nullable Cookie admin;

  private final Long adminId = TestIds.next();
  private final Long guestAccountId = TestIds.next();

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
    insertAccount(adminId, "ADMIN", "00000001");
    insertAccount(guestAccountId, "GUEST", "00000002");
  }

  @AfterEach
  void tearDown() {
    TestTables.clear(jdbc);
  }

  private static ResultMatcher satisfiesTheContract() {
    return openApi().isValid(SPEC);
  }

  /** For a request that is invalid on purpose: any complaint about the request is ignored. */
  private static ResultMatcher answersTheContractToAnInvalidRequest() {
    var validator =
        OpenApiInteractionValidator.createFor(SPEC)
            .withWhitelist(
                ValidationErrorsWhitelist.create()
                    .withRule("the request is invalid on purpose", WhitelistRules.isRequest()))
            .build();
    return openApi().isValid(validator);
  }

  // --- GET /admin/guests/{id}/stays ---

  @Test
  void theStaysAreListedNewestFirstAndPaged() throws Exception {
    var guest = insertGuest("12345678", "TEMPORARY", "ACTIVE");
    insertStay(guest, "2026-01-01", "2026-01-10", "100");
    insertStay(guest, "2026-09-01", "2026-09-10", "200.50");
    insertStay(guest, "2026-05-01", "2026-05-10", "300");
    insertStay(insertGuest("87654321", "TEMPORARY", "ACTIVE"), "2026-12-01", "2026-12-10", "400");

    var first =
        mockMvc
            .perform(get(stays(guest)).param("limit", "2").cookie(admin()))
            .andExpect(status().isOk())
            .andExpect(satisfiesTheContract())
            .andExpect(jsonPath("$.content.length()").value(2))
            .andExpect(jsonPath("$.content[0].startDate").value("2026-09-01"))
            .andExpect(jsonPath("$.content[0].agreedAmount").value(200.50))
            .andExpect(jsonPath("$.content[1].startDate").value("2026-05-01"))
            .andReturn();
    String cursor =
        com.jayway.jsonpath.JsonPath.read(first.getResponse().getContentAsString(), "$.nextCursor");

    mockMvc
        .perform(get(stays(guest)).param("limit", "2").param("after", cursor).cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].startDate").value("2026-01-01"))
        .andExpect(jsonPath("$.nextCursor").doesNotExist());
  }

  @Test
  void aGuestWithoutStaysHasAnEmptyList() throws Exception {
    var guest = insertGuest("12345678", "TEMPORARY", "ACTIVE");

    mockMvc
        .perform(get(stays(guest)).cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.content.length()").value(0));
  }

  @Test
  void aBadPageParameterIsAValidationError() throws Exception {
    var guest = insertGuest("12345678", "TEMPORARY", "ACTIVE");

    mockMvc
        .perform(get(stays(guest)).param("after", "not-a-cursor").cookie(admin()))
        .andExpect(status().isBadRequest())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.field").value("after"));
    mockMvc
        .perform(get(stays(guest)).param("limit", "0").cookie(admin()))
        .andExpect(status().isBadRequest())
        .andExpect(answersTheContractToAnInvalidRequest())
        .andExpect(jsonPath("$.field").value("limit"));
  }

  @Test
  void theStaysOfAnUnknownGuestAreNotFound() throws Exception {
    mockMvc
        .perform(get(URL + "/" + UUID.randomUUID() + "/stays").cookie(admin()))
        .andExpect(status().isNotFound())
        .andExpect(satisfiesTheContract());
    mockMvc
        .perform(
            get(URL + "/" + adminIds().mask(IdKind.GUEST, 999_999L) + "/stays").cookie(admin()))
        .andExpect(status().isNotFound())
        .andExpect(satisfiesTheContract());
  }

  // --- POST /admin/guests/{id}/stays ---

  @Test
  void aStayIsRegisteredForAnActiveTemporaryGuest() throws Exception {
    var guest = insertGuest("12345678", "TEMPORARY", "ACTIVE");

    var result =
        postStay(
                guest,
                "{\"startDate\":\"2026-10-10\",\"endDate\":\"2026-10-20\",\"agreedAmount\":300.50}")
            .andExpect(status().isCreated())
            .andExpect(satisfiesTheContract())
            .andExpect(jsonPath("$.startDate").value("2026-10-10"))
            .andExpect(jsonPath("$.endDate").value("2026-10-20"))
            .andExpect(jsonPath("$.agreedAmount").value(300.50))
            .andReturn();

    String id =
        com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    org.assertj.core.api.Assertions.assertThat(result.getResponse().getHeader("Location"))
        .endsWith("/stays/" + id);
    org.assertj.core.api.Assertions.assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM stays WHERE guest_id = ?", Integer.class, guest))
        .isEqualTo(1);
  }

  @Test
  void aFutureStayIsRegisteredWhileAnotherIsRunning() throws Exception {
    var guest = insertGuest("12345678", "TEMPORARY", "ACTIVE");
    insertStay(guest, "2026-10-01", "2026-10-20", "100");

    postStay(
            guest, "{\"startDate\":\"2026-10-21\",\"endDate\":\"2026-10-30\",\"agreedAmount\":100}")
        .andExpect(status().isCreated())
        .andExpect(satisfiesTheContract());
  }

  @Test
  void aStayThatCrossesAnotherIsAConflict() throws Exception {
    var guest = insertGuest("12345678", "TEMPORARY", "ACTIVE");
    insertStay(guest, "2026-10-10", "2026-10-20", "100");

    postStay(
            guest, "{\"startDate\":\"2026-10-20\",\"endDate\":\"2026-10-25\",\"agreedAmount\":100}")
        .andExpect(status().isConflict())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("STAY_OVERLAP"));
  }

  @Test
  void aStayForAGuestThatIsNotActiveIsAConflictThatNamesTheGuest() throws Exception {
    var guest = insertGuest("12345678", "TEMPORARY", "ONBOARDING");

    postStay(
            guest, "{\"startDate\":\"2026-10-10\",\"endDate\":\"2026-10-20\",\"agreedAmount\":100}")
        .andExpect(status().isConflict())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("GUEST_NOT_ACTIVE"))
        .andExpect(jsonPath("$.guestId").value(adminIds().mask(IdKind.GUEST, guest).toString()));
  }

  @Test
  void aStayForAContractGuestIsAConflict() throws Exception {
    var guest = insertGuest("12345678", "CONTRACT", "ACTIVE");

    postStay(
            guest, "{\"startDate\":\"2026-10-10\",\"endDate\":\"2026-10-20\",\"agreedAmount\":100}")
        .andExpect(status().isConflict())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("GUEST_NOT_TEMPORARY"));
  }

  @Test
  void aStayForAnUnknownGuestIsNotFound() throws Exception {
    mockMvc
        .perform(
            post(URL + "/" + adminIds().mask(IdKind.GUEST, 999_999L) + "/stays")
                .cookie(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"startDate\":\"2026-10-10\",\"endDate\":\"2026-10-20\",\"agreedAmount\":100}"))
        .andExpect(status().isNotFound())
        .andExpect(satisfiesTheContract());
  }

  @Test
  void invalidStaysAreValidationErrors() throws Exception {
    var guest = insertGuest("12345678", "TEMPORARY", "ACTIVE");

    // Both dates are well formed, so the contract allows them: the use case refuses the order.
    postStay(
            guest, "{\"startDate\":\"2026-10-20\",\"endDate\":\"2026-10-10\",\"agreedAmount\":100}")
        .andExpect(status().isBadRequest())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.field").value("endDate"));
    // The contract itself refuses these requests.
    for (var body :
        new String[] {
          "{\"startDate\":\"2026-10-10\",\"endDate\":\"2026-10-20\"}",
          "{\"startDate\":\"2026-10-10\",\"agreedAmount\":100}",
          "{\"startDate\":\"10/10/2026\",\"endDate\":\"2026-10-20\",\"agreedAmount\":100}",
          "{\"startDate\":\"2026-10-10\",\"endDate\":\"2026-10-20\",\"agreedAmount\":0}",
          "{\"startDate\":\"2026-10-10\",\"endDate\":\"2026-10-20\",\"agreedAmount\":10.001}",
          "{\"startDate\":\"2026-10-10\",\"endDate\":\"2026-10-20\",\"agreedAmount\":100000000}",
          "[]"
        }) {
      postStay(guest, body)
          .andExpect(status().isBadRequest())
          .andExpect(answersTheContractToAnInvalidRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
    org.assertj.core.api.Assertions.assertThat(
            jdbc.queryForObject("SELECT count(*) FROM stays", Integer.class))
        .isZero();
  }

  // --- PATCH /admin/guests/{id}/stays/{stayId} ---

  @Test
  void theDatesAndTheAmountOfAStayAreCorrected() throws Exception {
    var guest = insertGuest("12345678", "TEMPORARY", "ACTIVE");
    var stay = insertStay(guest, "2026-10-10", "2026-10-20", "100");

    patchStay(guest, stay, "{\"endDate\":\"2026-10-25\"}")
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.startDate").value("2026-10-10"))
        .andExpect(jsonPath("$.endDate").value("2026-10-25"))
        .andExpect(jsonPath("$.agreedAmount").value(100));
    patchStay(guest, stay, "{\"agreedAmount\":250.75}")
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.agreedAmount").value(250.75));
  }

  @Test
  void aStayCanBeCorrectedEvenIfTheGuestIsNoLongerActive() throws Exception {
    var guest = insertGuest("12345678", "TEMPORARY", "INACTIVE");
    var stay = insertStay(guest, "2026-10-10", "2026-10-20", "100");

    patchStay(guest, stay, "{\"agreedAmount\":120}")
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract());
  }

  @Test
  void correctedDatesThatCrossAnotherStayAreAConflict() throws Exception {
    var guest = insertGuest("12345678", "TEMPORARY", "ACTIVE");
    var stay = insertStay(guest, "2026-10-10", "2026-10-20", "100");
    insertStay(guest, "2026-11-01", "2026-11-10", "100");

    patchStay(guest, stay, "{\"endDate\":\"2026-11-01\"}")
        .andExpect(status().isConflict())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("STAY_OVERLAP"));
    // Keeping its own dates is not a crossing with itself.
    patchStay(guest, stay, "{\"startDate\":\"2026-10-10\",\"endDate\":\"2026-10-20\"}")
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract());
  }

  @Test
  void invalidCorrectionsAreValidationErrors() throws Exception {
    var guest = insertGuest("12345678", "TEMPORARY", "ACTIVE");
    var stay = insertStay(guest, "2026-10-10", "2026-10-20", "100");

    patchStay(guest, stay, "{\"startDate\":\"2026-10-25\"}")
        .andExpect(status().isBadRequest())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.field").value("endDate"));
    for (var body :
        new String[] {
          "{}",
          "{\"guestId\":\"x\"}",
          "{\"agreedAmount\":0}",
          "{\"agreedAmount\":10.001}",
          "{\"endDate\":\"not-a-date\"}"
        }) {
      patchStay(guest, stay, body)
          .andExpect(status().isBadRequest())
          .andExpect(answersTheContractToAnInvalidRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
  }

  @Test
  void anUnknownStayOrOneOfAnotherGuestIsNotFound() throws Exception {
    var guest = insertGuest("12345678", "TEMPORARY", "ACTIVE");
    var other = insertGuest("87654321", "TEMPORARY", "ACTIVE");
    var ofOther = insertStay(other, "2026-10-10", "2026-10-20", "100");

    patchStay(guest, 999_999L, "{\"agreedAmount\":120}")
        .andExpect(status().isNotFound())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("STAY_NOT_FOUND"));
    patchStay(guest, ofOther, "{\"agreedAmount\":120}")
        .andExpect(status().isNotFound())
        .andExpect(satisfiesTheContract());
    mockMvc
        .perform(
            patch(stays(guest) + "/" + UUID.randomUUID())
                .cookie(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"agreedAmount\":120}"))
        .andExpect(status().isNotFound())
        .andExpect(satisfiesTheContract());
    patchStay(999_999L, ofOther, "{\"agreedAmount\":120}")
        .andExpect(status().isNotFound())
        .andExpect(satisfiesTheContract());
  }

  // --- who may ---

  @Test
  void withoutSessionTheAnswerIs401AndAGuestSessionIs403() throws Exception {
    var guest = insertGuest("12345678", "TEMPORARY", "ACTIVE");
    var guestCookie = new Cookie("sessionToken", jwtTokenProvider.issue(guestAccountId.toString()));

    mockMvc
        .perform(get(stays(guest)))
        .andExpect(status().isUnauthorized())
        .andExpect(answersTheContractToAnInvalidRequest());
    mockMvc
        .perform(get(stays(guest)).cookie(guestCookie))
        .andExpect(status().isForbidden())
        .andExpect(satisfiesTheContract());
    mockMvc
        .perform(
            post(stays(guest))
                .cookie(guestCookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"startDate\":\"2026-10-10\",\"endDate\":\"2026-10-20\",\"agreedAmount\":100}"))
        .andExpect(status().isForbidden())
        .andExpect(satisfiesTheContract());
  }

  // --- helpers ---

  private String stays(Long guestId) {
    return URL + "/" + adminIds().mask(IdKind.GUEST, guestId) + "/stays";
  }

  /** The ids as the session of {@link #admin} shows them. */
  private MaskSession adminIds() {
    return SessionIds.of(masker, admin());
  }

  /** One session per test (TAR-227: its masked ids are valid only in it). */
  private Cookie admin() {
    Cookie session = admin;
    if (session == null) {
      session = new Cookie("sessionToken", jwtTokenProvider.issue(adminId.toString()));
      admin = session;
    }
    return session;
  }

  private ResultActions postStay(Long guest, String body) throws Exception {
    return mockMvc.perform(
        post(stays(guest)).cookie(admin()).contentType(MediaType.APPLICATION_JSON).content(body));
  }

  private ResultActions patchStay(Long guest, Long stay, String body) throws Exception {
    return mockMvc.perform(
        patch(stays(guest) + "/" + adminIds().mask(IdKind.STAY, stay))
            .cookie(admin())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private Long insertGuest(String number, String type, String status) {
    Long id = TestIds.next();
    jdbc.update(
        "INSERT INTO guests (id, full_name, document_type, document_number, guest_type, status)"
            + " VALUES (?, 'Ana Quispe', 'DNI', ?, ?, ?)",
        id,
        number,
        type,
        status);
    return id;
  }

  private Long insertStay(Long guest, String start, String end, String amount) {
    Long id = TestIds.next();
    jdbc.update(
        "INSERT INTO stays (id, guest_id, start_date, end_date, agreed_amount)"
            + " VALUES (?, ?, ?::date, ?::date, ?::numeric)",
        id,
        guest,
        start,
        end,
        amount);
    return id;
  }

  private void insertAccount(Long id, String role, String document) {
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " must_change_password, status) VALUES (?, ?, 'DNI', ?, 'hash', ?, FALSE, 'ACTIVE')",
        id,
        document + "@example.test",
        document,
        role);
  }
}
