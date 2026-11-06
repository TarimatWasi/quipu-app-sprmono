package com.tarimatwasi.quipu.contract;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static com.tarimatwasi.quipu.support.BrowserCsrf.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.whitelist.ValidationErrorsWhitelist;
import com.atlassian.oai.validator.whitelist.rule.WhitelistRules;
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
import java.nio.file.Path;
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
 * TAR-32: the real answers of the environment operations (RF-01) satisfy the shared contract, the
 * one of the tag pinned in pom.xml (contract.version).
 */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@AutoConfigureMockMvc
@ImportTestcontainers(PostgresContainers.class)
class EnvironmentsContractTest {

  private static final String SPEC =
      Path.of("target/contract/bff.yaml").toAbsolutePath().toString();
  private static final String URL = "/bff/admin/environments";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired JwtTokenProvider jwtTokenProvider;
  @Autowired IdMasker masker;

  /**
   * TAR-227: every account of a test signs in to this one session, so an id one request shows is
   * valid in the next.
   */
  private final String sessionId = SessionIds.newSessionId();

  private final Long adminId = TestIds.next();
  private final Long guestId = TestIds.next();

  /** The container is shared by all integration tests: this test owns users and environments. */
  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
    insertUser(adminId, "ADMIN", "00000001");
    insertUser(guestId, "GUEST", "00000002");
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
  void createGetEditAndListAnEnvironment() throws Exception {
    var created =
        create("{\"code\":\"201\",\"type\":\"ROOM\"}")
            .andExpect(status().isCreated())
            .andExpect(satisfiesTheContract());
    var id = idOf(created);

    mockMvc
        .perform(get(URL + "/" + id).cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract());
    mockMvc
        .perform(
            patch(URL + "/" + id)
                .with(csrf())
                .cookie(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"type\":\"CABIN\"}"))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract());
    mockMvc
        .perform(get(URL).param("status", "ALL").cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract());
  }

  @Test
  void aDuplicateCodeIsTheConflictOfTheContract() throws Exception {
    create("{\"code\":\"201\",\"type\":\"ROOM\"}").andExpect(status().isCreated());

    create("{\"code\":\"201\",\"type\":\"CABIN\"}")
        .andExpect(status().isConflict())
        .andExpect(satisfiesTheContract());
  }

  @Test
  void aBlankCodeIsAValidationError() throws Exception {
    create("{\"code\":\"   \",\"type\":\"ROOM\"}")
        .andExpect(status().isBadRequest())
        .andExpect(answersTheContractToARequestWith("validation.request.body.schema.pattern"));
  }

  @Test
  void anEmptyEditIsAValidationError() throws Exception {
    var id = idOf(create("{\"code\":\"201\",\"type\":\"ROOM\"}"));

    mockMvc
        .perform(
            patch(URL + "/" + id)
                .with(csrf())
                .cookie(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0}"))
        .andExpect(status().isBadRequest())
        .andExpect(
            answersTheContractToARequestWith("validation.request.body.schema.minProperties"));
  }

  @Test
  void anUnknownEnvironmentIsNotFound() throws Exception {
    mockMvc
        .perform(get(URL + "/" + UUID.randomUUID()).cookie(admin()))
        .andExpect(status().isNotFound())
        .andExpect(satisfiesTheContract());
  }

  @Test
  void withoutSessionTheAnswerIs401() throws Exception {
    mockMvc
        .perform(get(URL))
        .andExpect(status().isUnauthorized())
        .andExpect(answersTheContractToARequestWith("validation.request.security.missing"));
  }

  @Test
  void deactivateAndReactivateAnswerTheContract() throws Exception {
    var id = idOf(create("{\"code\":\"201\",\"type\":\"ROOM\"}"));

    for (var action : new String[] {"deactivate", "reactivate"}) {
      mockMvc
          .perform(post(URL + "/" + id + "/" + action).with(csrf()).cookie(admin()))
          .andExpect(status().isNoContent())
          .andExpect(satisfiesTheContract());
      mockMvc
          .perform(post(URL + "/" + UUID.randomUUID() + "/" + action).with(csrf()).cookie(admin()))
          .andExpect(status().isNotFound())
          .andExpect(satisfiesTheContract());
      mockMvc
          .perform(
              post(URL + "/" + id + "/" + action)
                  .with(csrf())
                  .cookie(
                      new Cookie(
                          "sessionToken", jwtTokenProvider.renew(guestId.toString(), sessionId))))
          .andExpect(status().isForbidden())
          .andExpect(satisfiesTheContract());
    }
  }

  /** RN-12, TAR-237: the 409 of an environment held by a contract names the contract and whose. */
  @Test
  void anEnvironmentHeldByAContractSaysWhoHoldsIt() throws Exception {
    Long room = TestIds.next();
    jdbc.update(
        "INSERT INTO environments (id, code, type, status) VALUES (?, '201', 'ROOM', 'ACTIVE')",
        room);
    Long guest = TestIds.next();
    jdbc.update(
        "INSERT INTO guests (id, full_name, document_type, document_number, guest_type, status)"
            + " VALUES (?, 'Ana Quispe', 'DNI', '80000001', 'CONTRACT', 'ACTIVE')",
        guest);
    Long contract = TestIds.next();
    jdbc.update(
        "INSERT INTO contracts (id, guest_id, environment_id, start_date, end_date, monthly_amount,"
            + " payment_day, grace_days, file_key, original_name, size_bytes, status)"
            + " VALUES (?, ?, ?, '2025-01-01', NULL, 800, 5, 3, 'contracts/seed.pdf', 'seed.pdf',"
            + " 10, 'ACTIVE')",
        contract,
        guest,
        room);

    mockMvc
        .perform(
            post(URL + "/" + ids().mask(IdKind.ENVIRONMENT, room) + "/deactivate")
                .with(csrf())
                .cookie(admin()))
        .andExpect(status().isConflict())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("ENVIRONMENT_OCCUPIED"))
        .andExpect(jsonPath("$.occupant.kind").value("CONTRACT"))
        .andExpect(jsonPath("$.occupant.guestId").value(ids().mask(IdKind.GUEST, guest).toString()))
        .andExpect(jsonPath("$.occupant.guestName").value("Ana Quispe"))
        .andExpect(
            jsonPath("$.occupant.contractId")
                .value(ids().mask(IdKind.CONTRACT, contract).toString()))
        .andExpect(jsonPath("$.occupant.stayId").isEmpty())
        .andExpect(jsonPath("$.occupant.startDate").value("2025-01-01"))
        .andExpect(jsonPath("$.occupant.endDate").isEmpty());
  }

  @Test
  void aGuestSessionIs403() throws Exception {
    var guest = new Cookie("sessionToken", jwtTokenProvider.renew(guestId.toString(), sessionId));

    mockMvc
        .perform(get(URL).cookie(guest))
        .andExpect(status().isForbidden())
        .andExpect(satisfiesTheContract());
  }

  private MaskSession ids() {
    return masker.session(sessionId);
  }

  private Cookie admin() {
    return new Cookie("sessionToken", jwtTokenProvider.renew(adminId.toString(), sessionId));
  }

  private ResultActions create(String body) throws Exception {
    return mockMvc.perform(
        post(URL)
            .with(csrf())
            .cookie(admin())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private static String idOf(ResultActions created) throws Exception {
    var id = JsonPath.<String>read(created.andReturn().getResponse().getContentAsString(), "$.id");
    assertThat(id).isNotBlank();
    return id;
  }

  private void insertUser(Long id, String role, String document) {
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " must_change_password, status) VALUES (?, ?, 'DNI', ?, 'hash', ?, FALSE, 'ACTIVE')",
        id,
        document + "@example.test",
        document,
        role);
  }
}
