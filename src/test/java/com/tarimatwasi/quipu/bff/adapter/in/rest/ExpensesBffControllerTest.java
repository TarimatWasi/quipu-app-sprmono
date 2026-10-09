package com.tarimatwasi.quipu.bff.adapter.in.rest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.tarimatwasi.quipu.auth.adapter.out.security.JwtTokenProvider;
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

/** RF-07 end to end: roles, validation, the month filter and the free edit and delete (RN-24). */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@AutoConfigureMockMvc
@ImportTestcontainers(PostgresContainers.class)
class ExpensesBffControllerTest {

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

  @Test
  void anAdminRegistersAnExpenseAndSeesItInItsMonth() throws Exception {
    create(WATER)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.category").value("WATER"))
        .andExpect(jsonPath("$.amount").value(120.5))
        .andExpect(jsonPath("$.month").value("2026-09"))
        .andExpect(jsonPath("$.description").value("Recibo"));

    mockMvc
        .perform(get(URL).param("month", "2026-09").cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));
    mockMvc
        .perform(get(URL).param("month", "2026-10").cookie(admin()))
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void theDescriptionIsOptionalAndLeftOutOfTheAnswer() throws Exception {
    create("{\"category\":\"OTHER\",\"amount\":10,\"month\":\"2026-09\"}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.description").doesNotExist());
    create("{\"category\":\"OTHER\",\"amount\":10,\"month\":\"2026-09\",\"description\":\"  \"}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.description").doesNotExist());
  }

  @Test
  void theListingOfAMonthIsOldestFirst() throws Exception {
    create("{\"category\":\"WATER\",\"amount\":1,\"month\":\"2026-09\"}");
    create("{\"category\":\"INTERNET\",\"amount\":2,\"month\":\"2026-09\"}");
    create("{\"category\":\"OTHER\",\"amount\":3,\"month\":\"2026-09\"}");

    mockMvc
        .perform(get(URL).param("month", "2026-09").cookie(admin()))
        .andExpect(jsonPath("$[0].category").value("WATER"))
        .andExpect(jsonPath("$[1].category").value("INTERNET"))
        .andExpect(jsonPath("$[2].category").value("OTHER"));
  }

  @Test
  void invalidDataIs400WithTheField() throws Exception {
    var cases =
        new String[][] {
          {"{\"category\":\"WATER\",\"amount\":0,\"month\":\"2026-09\"}", "amount"},
          {"{\"category\":\"WATER\",\"amount\":-5,\"month\":\"2026-09\"}", "amount"},
          {"{\"category\":\"WATER\",\"amount\":10.123,\"month\":\"2026-09\"}", "amount"},
          {"{\"category\":\"WATER\",\"amount\":100000000,\"month\":\"2026-09\"}", "amount"},
          {"{\"category\":\"WATER\",\"month\":\"2026-09\"}", "amount"},
          {"{\"category\":\"WATER\",\"amount\":10,\"month\":\"2026-13\"}", "month"},
          {"{\"category\":\"WATER\",\"amount\":10,\"month\":\"2026-9\"}", "month"},
          {"{\"category\":\"WATER\",\"amount\":10}", "month"},
          {"{\"amount\":10,\"month\":\"2026-09\"}", "category"},
        };
    for (var c : cases) {
      create(c[0])
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
          .andExpect(jsonPath("$.field").value(c[1]));
    }
    create("{\"category\":\"GAS\",\"amount\":10,\"month\":\"2026-09\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    create(
            "{\"category\":\"WATER\",\"amount\":10,\"month\":\"2026-09\",\"description\":\""
                + "x".repeat(501)
                + "\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("description"));
  }

  /** The contract asks for a JSON number: a quoted amount is not converted. */
  @Test
  void aQuotedAmountIs400() throws Exception {
    create("{\"category\":\"WATER\",\"amount\":\"10\",\"month\":\"2026-09\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void anAmountWithTrailingZerosIsAccepted() throws Exception {
    create("{\"category\":\"WATER\",\"amount\":10.500,\"month\":\"2026-09\"}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.amount").value(10.5));
  }

  @Test
  void theDescriptionLimitCountsCharactersNotUtf16Units() throws Exception {
    var emoji = "😀";
    var json = "{\"category\":\"WATER\",\"amount\":10,\"month\":\"2026-09\",\"description\":\"";

    create(json + emoji.repeat(500) + "\"}").andExpect(status().isCreated());
    create(json + emoji.repeat(501) + "\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("description"));
    create(json + " ".repeat(500) + "x\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("description"));
  }

  @Test
  void aDescriptionWithANulCharacterIs400OnCreateAndEdit() throws Exception {
    var id = idOf(create(WATER));
    var body =
        "{\"category\":\"WATER\",\"amount\":10,\"month\":\"2026-09\",\"description\":\"x\\u0000y\"}";

    create(body)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("description"));
    mockMvc
        .perform(
            patch(URL + "/" + id)
                .cookie(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("description"));
  }

  @Test
  void theMonthOfTheListingIsRequiredAndWellFormed() throws Exception {
    for (var month : new String[] {"+10000-01", "20260-01", "2026-9", "2026-09-01", "  "}) {
      mockMvc
          .perform(get(URL).param("month", month).cookie(admin()))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.field").value("month"));
    }
    mockMvc
        .perform(get(URL).cookie(admin()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("month"));
    mockMvc
        .perform(get(URL).param("month", "2026-13").cookie(admin()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("month"));
  }

  @Test
  void anExpenseIsEditedFreelyAndMovesToAnotherMonth() throws Exception {
    var id = idOf(create(WATER));

    mockMvc
        .perform(
            patch(URL + "/" + id)
                .cookie(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\":\"INTERNET\",\"amount\":99.9,\"month\":\"2026-10\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.category").value("INTERNET"))
        .andExpect(jsonPath("$.month").value("2026-10"))
        .andExpect(jsonPath("$.description").doesNotExist());
    mockMvc
        .perform(get(URL).param("month", "2026-09").cookie(admin()))
        .andExpect(jsonPath("$.length()").value(0));
    mockMvc
        .perform(get(URL).param("month", "2026-10").cookie(admin()))
        .andExpect(jsonPath("$.length()").value(1));
  }

  @Test
  void anExpenseIsDeletedForGood() throws Exception {
    var id = idOf(create(WATER));

    mockMvc.perform(delete(URL + "/" + id).cookie(admin())).andExpect(status().isNoContent());

    mockMvc
        .perform(get(URL).param("month", "2026-09").cookie(admin()))
        .andExpect(jsonPath("$.length()").value(0));
    mockMvc
        .perform(delete(URL + "/" + id).cookie(admin()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("EXPENSE_NOT_FOUND"));
  }

  @Test
  void editingAnUnknownOrMalformedIdIs404Or400() throws Exception {
    mockMvc
        .perform(
            patch(URL + "/" + UUID.randomUUID())
                .cookie(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(WATER))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            patch(URL + "/not-a-uuid")
                .cookie(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(WATER))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("id"));
  }

  @Test
  void withoutSessionIs401AndAGuestIs403AndNothingIsWritten() throws Exception {
    var id = idOf(create(WATER));
    var guest = new Cookie("sessionToken", jwtTokenProvider.issue(guestId.toString(), "GUEST"));

    mockMvc.perform(get(URL).param("month", "2026-09")).andExpect(status().isUnauthorized());
    mockMvc
        .perform(get(URL).param("month", "2026-09").cookie(guest))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    mockMvc
        .perform(post(URL).cookie(guest).contentType(MediaType.APPLICATION_JSON).content(WATER))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            patch(URL + "/" + id)
                .cookie(guest)
                .contentType(MediaType.APPLICATION_JSON)
                .content(WATER))
        .andExpect(status().isForbidden());
    mockMvc.perform(delete(URL + "/" + id).cookie(guest)).andExpect(status().isForbidden());

    mockMvc
        .perform(get(URL).param("month", "2026-09").cookie(admin()))
        .andExpect(jsonPath("$.length()").value(1));
  }

  private Cookie admin() {
    return new Cookie("sessionToken", jwtTokenProvider.issue(adminId.toString(), "ADMIN"));
  }

  private ResultActions create(String body) throws Exception {
    return mockMvc.perform(
        post(URL).cookie(admin()).contentType(MediaType.APPLICATION_JSON).content(body));
  }

  private static String idOf(ResultActions created) throws Exception {
    return JsonPath.read(created.andReturn().getResponse().getContentAsString(), "$.id");
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
