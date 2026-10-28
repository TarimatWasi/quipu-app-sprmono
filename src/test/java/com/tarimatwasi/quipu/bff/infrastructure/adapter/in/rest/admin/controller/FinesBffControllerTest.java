package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.tarimatwasi.quipu.auth.infrastructure.adapter.out.security.JwtTokenProvider;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
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

/** RF-19 end to end: roles, validation, the month of the listing and the free deletion (RN-24). */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@AutoConfigureMockMvc
@ImportTestcontainers(PostgresContainers.class)
class FinesBffControllerTest {

  private static final String URL = "/bff/admin/fines";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired JwtTokenProvider jwtTokenProvider;
  @Autowired IdMasker ids;

  private final Long adminId = TestIds.next();
  private final Long guestUserId = TestIds.next();
  private Long guestId;
  private UUID maskedGuest;

  /** The container is shared by all integration tests: this test owns users, guests and fines. */
  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
    insertUser(adminId, "ADMIN", "00000001");
    insertUser(guestUserId, "GUEST", "00000002");
    guestId = insertGuest("ACTIVE");
    maskedGuest = ids.mask(IdKind.GUEST, guestId);
  }

  private String fine(String amount, String month) {
    return "{\"guestId\":\""
        + maskedGuest
        + "\",\"amount\":"
        + amount
        + ",\"month\":\""
        + month
        + "\"}";
  }

  @Test
  void anAdminRegistersAFineAndGetsItsLocation() throws Exception {
    var response =
        create(
                "{\"guestId\":\""
                    + maskedGuest
                    + "\",\"amount\":15.50,\"month\":\"2026-09\",\"description\":\" Ruido \"}")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.guestId").value(maskedGuest.toString()))
            .andExpect(jsonPath("$.amount").value(15.5))
            .andExpect(jsonPath("$.month").value("2026-09"))
            .andExpect(jsonPath("$.description").value("Ruido"))
            .andReturn()
            .getResponse();
    String id = JsonPath.read(response.getContentAsString(), "$.id");

    assertThat(response.getHeader("Location")).isEqualTo(URL + "/" + id);
    assertThat(ids.unmask(IdKind.FINE, UUID.fromString(id)))
        .isEqualTo(jdbc.queryForObject("SELECT id FROM fines", Long.class));
  }

  @Test
  void anInactiveGuestCanBeFined() throws Exception {
    var inactive = ids.mask(IdKind.GUEST, insertGuest("INACTIVE"));

    create("{\"guestId\":\"" + inactive + "\",\"amount\":10,\"month\":\"2026-09\"}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.guestId").value(inactive.toString()));
  }

  @Test
  void theDescriptionIsOptionalAndLeftOutOfTheAnswer() throws Exception {
    create(fine("10", "2026-09"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.description").doesNotExist());
    create(
            "{\"guestId\":\""
                + maskedGuest
                + "\",\"amount\":10,\"month\":\"2026-09\",\"description\":\"   \"}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.description").doesNotExist());
  }

  @Test
  void theListingHasOnlyTheFinesOfTheMonthOldestFirstAndUnpaged() throws Exception {
    create(fine("1", "2026-09")).andExpect(status().isCreated());
    create(fine("2", "2026-08")).andExpect(status().isCreated());
    create(fine("3", "2026-09")).andExpect(status().isCreated());
    create(fine("4", "2026-10")).andExpect(status().isCreated());

    mockMvc
        .perform(get(URL).param("month", "2026-09").cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].amount").value(1))
        .andExpect(jsonPath("$[0].month").value("2026-09"))
        .andExpect(jsonPath("$[1].amount").value(3))
        .andExpect(jsonPath("$[1].month").value("2026-09"));
    mockMvc
        .perform(get(URL).param("month", "2026-11").cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void invalidDataIs400WithTheField() throws Exception {
    var guest = "\"guestId\":\"" + maskedGuest + "\",";
    var cases =
        new String[][] {
          {"{\"amount\":10,\"month\":\"2026-09\"}", "guestId"},
          {"{\"guestId\":\"not-a-uuid\",\"amount\":10,\"month\":\"2026-09\"}", "guestId"},
          {"{" + guest + "\"month\":\"2026-09\"}", "amount"},
          {"{" + guest + "\"amount\":0,\"month\":\"2026-09\"}", "amount"},
          {"{" + guest + "\"amount\":-5,\"month\":\"2026-09\"}", "amount"},
          {"{" + guest + "\"amount\":10.123,\"month\":\"2026-09\"}", "amount"},
          {"{" + guest + "\"amount\":100000000,\"month\":\"2026-09\"}", "amount"},
          {"{" + guest + "\"amount\":10}", "month"},
          {"{" + guest + "\"amount\":10,\"month\":\"2026-13\"}", "month"},
          {"{" + guest + "\"amount\":10,\"month\":\"2026-9\"}", "month"},
          {"{" + guest + "\"amount\":10,\"month\":\"2026-09-01\"}", "month"},
          {
            "{"
                + guest
                + "\"amount\":10,\"month\":\"2026-09\",\"description\":\""
                + "x".repeat(501)
                + "\"}",
            "description"
          },
          {
            "{" + guest + "\"amount\":10,\"month\":\"2026-09\",\"description\":\"x\\u0000y\"}",
            "description"
          },
        };
    for (var c : cases) {
      create(c[0])
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
          .andExpect(jsonPath("$.field").value(c[1]));
    }
    assertThat(jdbc.queryForObject("SELECT count(*) FROM fines", Integer.class)).isZero();
  }

  @Test
  void theLimitsOfTheAmountAndTheDescriptionAreInclusive() throws Exception {
    create(fine("99999999.99", "2026-09"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.amount").value(99999999.99));
    create(fine("0.01", "2026-09")).andExpect(status().isCreated());
    create(
            "{\"guestId\":\""
                + maskedGuest
                + "\",\"amount\":10,\"month\":\"2026-09\",\"description\":\""
                + "😀".repeat(500)
                + "\"}")
        .andExpect(status().isCreated());
  }

  /** Year 1 and year 9999 are stored and read back as they are. */
  @Test
  void theEdgeYearsAreStoredAndListedAsTheyAre() throws Exception {
    for (var month : new String[] {"0001-01", "9999-12"}) {
      create(fine("10", month))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.month").value(month));
      mockMvc
          .perform(get(URL).param("month", month).cookie(admin()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.length()").value(1))
          .andExpect(jsonPath("$[0].month").value(month));
    }
  }

  /** Year 0000 matches the pattern, but PostgreSQL would read it back as year 1 (TAR-47). */
  @Test
  void yearZeroIs400OnCreateAndList() throws Exception {
    create(fine("10", "0000-12"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.field").value("month"));
    mockMvc
        .perform(get(URL).param("month", "0000-12").cookie(admin()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.field").value("month"));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM fines", Integer.class)).isZero();
  }

  @Test
  void theMonthOfTheListingIsRequiredAndWellFormed() throws Exception {
    for (var month : new String[] {"2026-13", "2026-9", "2026-09-01", "+10000-01", "  "}) {
      mockMvc
          .perform(get(URL).param("month", month).cookie(admin()))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
          .andExpect(jsonPath("$.field").value("month"));
    }
    mockMvc
        .perform(get(URL).cookie(admin()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.field").value("month"));
  }

  @Test
  void aGuestThatDoesNotExistIs404AndNothingIsWritten() throws Exception {
    var unknown = ids.mask(IdKind.GUEST, TestIds.next());
    var forged = UUID.randomUUID();

    for (var guest : new UUID[] {unknown, forged}) {
      create("{\"guestId\":\"" + guest + "\",\"amount\":10,\"month\":\"2026-09\"}")
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }
    assertThat(jdbc.queryForObject("SELECT count(*) FROM fines", Integer.class)).isZero();
  }

  /** The data is checked before the guest: a forged guest with a bad amount is still a 400. */
  @Test
  void invalidDataWithAnUnknownGuestIs400() throws Exception {
    create("{\"guestId\":\"" + UUID.randomUUID() + "\",\"amount\":0,\"month\":\"2026-09\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("amount"));
  }

  @Test
  void aFineIsDeletedForGoodAndASecondDeleteIs404() throws Exception {
    var id = idOf(create(fine("10", "2026-09")));

    mockMvc
        .perform(delete(URL + "/" + id).cookie(admin()))
        .andExpect(status().isNoContent())
        .andExpect(result -> assertThat(result.getResponse().getContentAsString()).isEmpty());

    assertThat(jdbc.queryForObject("SELECT count(*) FROM fines", Integer.class)).isZero();
    mockMvc
        .perform(delete(URL + "/" + id).cookie(admin()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
  }

  @Test
  void deletingAnUnknownOrMalformedIdIs404Or400() throws Exception {
    mockMvc
        .perform(delete(URL + "/" + UUID.randomUUID()).cookie(admin()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    mockMvc
        .perform(delete(URL + "/" + ids.mask(IdKind.FINE, TestIds.next())).cookie(admin()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    // An id issued for another kind does not unmask as a fine.
    mockMvc
        .perform(delete(URL + "/" + maskedGuest).cookie(admin()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    mockMvc
        .perform(delete(URL + "/not-a-uuid").cookie(admin()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("id"));
  }

  @Test
  void withoutSessionIs401AndAGuestIs403AndNothingIsWritten() throws Exception {
    var id = idOf(create(fine("10", "2026-09")));
    var guest = new Cookie("sessionToken", jwtTokenProvider.issue(guestUserId.toString()));

    mockMvc.perform(get(URL).param("month", "2026-09")).andExpect(status().isUnauthorized());
    mockMvc
        .perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(fine("10", "2026-09")))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(delete(URL + "/" + id)).andExpect(status().isUnauthorized());
    mockMvc
        .perform(get(URL).param("month", "2026-09").cookie(guest))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    mockMvc
        .perform(
            post(URL)
                .cookie(guest)
                .contentType(MediaType.APPLICATION_JSON)
                .content(fine("10", "2026-09")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    mockMvc
        .perform(delete(URL + "/" + id).cookie(guest))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

    assertThat(jdbc.queryForObject("SELECT count(*) FROM fines", Integer.class)).isOne();
  }

  private Cookie admin() {
    return new Cookie("sessionToken", jwtTokenProvider.issue(adminId.toString()));
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

  private Long insertGuest(String status) {
    Long id = TestIds.next();
    jdbc.update(
        "INSERT INTO guests (id, document_type, document_number, guest_type, status)"
            + " VALUES (?, 'DNI', ?, 'CONTRACT', ?)",
        id,
        String.valueOf(id).substring(2),
        status);
    return id;
  }
}
