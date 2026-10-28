package com.tarimatwasi.quipu.contract;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.whitelist.ValidationErrorsWhitelist;
import com.atlassian.oai.validator.whitelist.rule.WhitelistRules;
import com.jayway.jsonpath.JsonPath;
import com.tarimatwasi.quipu.auth.infrastructure.adapter.out.security.JwtTokenProvider;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.support.PostgresContainers;
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
 * TAR-47: the real answers of the fine operations (RF-19) satisfy the shared contract, the one of
 * the tag pinned in pom.xml (contract.version).
 */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@AutoConfigureMockMvc
@ImportTestcontainers(PostgresContainers.class)
class FinesContractTest {

  private static final String SPEC =
      Path.of("target/contract/bff.yaml").toAbsolutePath().toString();
  private static final String URL = "/bff/admin/fines";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired JwtTokenProvider jwtTokenProvider;
  @Autowired IdMasker ids;

  private final Long adminId = TestIds.next();
  private final Long guestUserId = TestIds.next();
  private UUID maskedGuest;

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
    insertUser(adminId, "ADMIN", "00000001");
    insertUser(guestUserId, "GUEST", "00000002");
    Long guestId = TestIds.next();
    jdbc.update(
        "INSERT INTO guests (id, document_type, document_number, guest_type, status)"
            + " VALUES (?, 'DNI', '40000001', 'TEMPORARY', 'INACTIVE')",
        guestId);
    maskedGuest = ids.mask(IdKind.GUEST, guestId);
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

  private String body(UUID guest, String amount, String month, String extra) {
    return "{\"guestId\":\""
        + guest
        + "\",\"amount\":"
        + amount
        + ",\"month\":\""
        + month
        + "\""
        + extra
        + "}";
  }

  @Test
  void createListAndDeleteAFine() throws Exception {
    var created =
        create(body(maskedGuest, "15.50", "2026-09", ",\"description\":\"Ruido\""))
            .andExpect(status().isCreated())
            .andExpect(header().exists("Location"))
            .andExpect(satisfiesTheContract());
    var id = JsonPath.<String>read(created.andReturn().getResponse().getContentAsString(), "$.id");
    create(body(maskedGuest, "3", "2026-09", "")).andExpect(satisfiesTheContract());

    mockMvc
        .perform(get(URL).param("month", "2026-09").cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(satisfiesTheContract());
    mockMvc
        .perform(delete(URL + "/" + id).cookie(admin()))
        .andExpect(status().isNoContent())
        .andExpect(satisfiesTheContract());
    mockMvc
        .perform(delete(URL + "/" + id).cookie(admin()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
        .andExpect(satisfiesTheContract());
  }

  @Test
  void aGuestThatDoesNotExistIs404() throws Exception {
    create(body(ids.mask(IdKind.GUEST, TestIds.next()), "10", "2026-09", ""))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
        .andExpect(satisfiesTheContract());
  }

  @Test
  void theLimitsOfTheBodyAreValidationErrors() throws Exception {
    create(body(maskedGuest, "0", "2026-09", ""))
        .andExpect(status().isBadRequest())
        .andExpect(answersTheContractToARequestWith("validation.request.body.schema.minimum"));
    create(body(maskedGuest, "100000000", "2026-09", ""))
        .andExpect(status().isBadRequest())
        .andExpect(answersTheContractToARequestWith("validation.request.body.schema.maximum"));
    create(body(maskedGuest, "10.123", "2026-09", ""))
        .andExpect(status().isBadRequest())
        .andExpect(answersTheContractToARequestWith("validation.request.body.schema.multipleOf"));
    create(body(maskedGuest, "10", "2026-13", ""))
        .andExpect(status().isBadRequest())
        .andExpect(answersTheContractToARequestWith("validation.request.body.schema.pattern"));
    create(body(maskedGuest, "10", "2026-09", ",\"description\":\"" + "x".repeat(501) + "\""))
        .andExpect(status().isBadRequest())
        .andExpect(answersTheContractToARequestWith("validation.request.body.schema.maxLength"));
    create("{\"guestId\":\"not-a-uuid\",\"amount\":10,\"month\":\"2026-09\"}")
        .andExpect(status().isBadRequest())
        .andExpect(answersTheContractToARequestWith("validation.request.body.schema.format.uuid"));
    create("{\"amount\":10,\"month\":\"2026-09\"}")
        .andExpect(status().isBadRequest())
        .andExpect(answersTheContractToARequestWith("validation.request.body.schema.required"));
  }

  @Test
  void aMonthOutOfRangeInTheListingIsAValidationError() throws Exception {
    mockMvc
        .perform(get(URL).param("month", "2026-13").cookie(admin()))
        .andExpect(status().isBadRequest())
        .andExpect(answersTheContractToARequestWith("validation.request.parameter.schema.pattern"));
    mockMvc
        .perform(get(URL).cookie(admin()))
        .andExpect(status().isBadRequest())
        .andExpect(answersTheContractToARequestWith("validation.request.parameter.query.missing"));
  }

  @Test
  void withoutSessionIs401AndAGuestIs403() throws Exception {
    var guest = new Cookie("sessionToken", jwtTokenProvider.issue(guestUserId.toString()));

    mockMvc
        .perform(get(URL).param("month", "2026-09"))
        .andExpect(status().isUnauthorized())
        .andExpect(answersTheContractToARequestWith("validation.request.security.missing"));
    mockMvc
        .perform(get(URL).param("month", "2026-09").cookie(guest))
        .andExpect(status().isForbidden())
        .andExpect(satisfiesTheContract());
    mockMvc
        .perform(
            post(URL)
                .cookie(guest)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(maskedGuest, "10", "2026-09", "")))
        .andExpect(status().isForbidden())
        .andExpect(satisfiesTheContract());
    mockMvc
        .perform(delete(URL + "/" + ids.mask(IdKind.FINE, 1L)).cookie(guest))
        .andExpect(status().isForbidden())
        .andExpect(satisfiesTheContract());
  }

  private Cookie admin() {
    return new Cookie("sessionToken", jwtTokenProvider.issue(adminId.toString()));
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
