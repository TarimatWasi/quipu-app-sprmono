package com.tarimatwasi.quipu.contract;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.whitelist.ValidationErrorsWhitelist;
import com.atlassian.oai.validator.whitelist.rule.WhitelistRules;
import com.jayway.jsonpath.JsonPath;
import com.tarimatwasi.quipu.auth.adapter.out.security.JwtTokenProvider;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import jakarta.servlet.http.Cookie;
import java.nio.file.Path;
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
 * TAR-46: the real answers of the expense operations (RF-07) satisfy the shared contract, the one
 * of the tag pinned in pom.xml (contract.version).
 */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@AutoConfigureMockMvc
@ImportTestcontainers(PostgresContainers.class)
class ExpensesContractTest {

  private static final String SPEC =
      Path.of("target/contract/bff.yaml").toAbsolutePath().toString();
  private static final String URL = "/bff/admin/expenses";
  private static final String WATER =
      "{\"category\":\"WATER\",\"amount\":120.50,\"month\":\"2026-09\",\"description\":\"Recibo\"}";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired JwtTokenProvider jwtTokenProvider;

  private final Long adminId = TestIds.next();
  private final Long guestId = TestIds.next();

  /** The container is shared by all integration tests: this test owns users and expenses. */
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
  void createListEditAndDeleteAnExpense() throws Exception {
    var created = create(WATER).andExpect(status().isCreated()).andExpect(satisfiesTheContract());
    var id = JsonPath.<String>read(created.andReturn().getResponse().getContentAsString(), "$.id");

    mockMvc
        .perform(get(URL).param("month", "2026-09").cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract());
    mockMvc
        .perform(
            patch(URL + "/" + id)
                .cookie(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\":\"INTERNET\",\"amount\":99.9,\"month\":\"2026-10\"}"))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract());
    mockMvc
        .perform(delete(URL + "/" + id).cookie(admin()))
        .andExpect(status().isNoContent())
        .andExpect(satisfiesTheContract());
    mockMvc
        .perform(delete(URL + "/" + id).cookie(admin()))
        .andExpect(status().isNotFound())
        .andExpect(satisfiesTheContract());
  }

  @Test
  void anAmountOfZeroIsAValidationError() throws Exception {
    create("{\"category\":\"WATER\",\"amount\":0,\"month\":\"2026-09\"}")
        .andExpect(status().isBadRequest())
        .andExpect(answersTheContractToARequestWith("validation.request.body.schema.minimum"));
  }

  @Test
  void aMonthOutOfRangeIsAValidationError() throws Exception {
    create("{\"category\":\"WATER\",\"amount\":10,\"month\":\"2026-13\"}")
        .andExpect(status().isBadRequest())
        .andExpect(answersTheContractToARequestWith("validation.request.body.schema.pattern"));
    mockMvc
        .perform(get(URL).param("month", "2026-13").cookie(admin()))
        .andExpect(status().isBadRequest())
        .andExpect(answersTheContractToARequestWith("validation.request.parameter.schema.pattern"));
  }

  @Test
  void withoutSessionIs401AndAGuestIs403() throws Exception {
    var guest = new Cookie("sessionToken", jwtTokenProvider.issue(guestId.toString(), "GUEST"));

    mockMvc
        .perform(get(URL).param("month", "2026-09"))
        .andExpect(status().isUnauthorized())
        .andExpect(answersTheContractToARequestWith("validation.request.security.missing"));
    mockMvc
        .perform(get(URL).param("month", "2026-09").cookie(guest))
        .andExpect(status().isForbidden())
        .andExpect(satisfiesTheContract());
  }

  private Cookie admin() {
    return new Cookie("sessionToken", jwtTokenProvider.issue(adminId.toString(), "ADMIN"));
  }

  private ResultActions create(String body) throws Exception {
    return mockMvc.perform(
        post(URL).cookie(admin()).contentType(MediaType.APPLICATION_JSON).content(body));
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
