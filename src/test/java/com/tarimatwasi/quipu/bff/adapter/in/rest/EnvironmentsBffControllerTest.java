package com.tarimatwasi.quipu.bff.adapter.in.rest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tarimatwasi.quipu.auth.adapter.out.security.JwtTokenProvider;
import com.tarimatwasi.quipu.support.PostgresContainers;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
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

/** RF-01 end to end: roles, validation, conflicts and the listing filter of the environments. */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@AutoConfigureMockMvc
@ImportTestcontainers(PostgresContainers.class)
class EnvironmentsBffControllerTest {

  private static final String URL = "/bff/admin/environments";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired JwtTokenProvider jwtTokenProvider;

  private final UUID adminId = UUID.randomUUID();
  private final UUID guestId = UUID.randomUUID();

  /**
   * The container is shared by all integration tests: this test owns the users and environments.
   */
  @BeforeEach
  void setUp() {
    jdbc.update("DELETE FROM environments");
    jdbc.update("DELETE FROM users");
    insertUser(adminId, "ADMIN", "00000001");
    insertUser(guestId, "GUEST", "00000002");
  }

  @Test
  void anAdminCreatesAnActiveEnvironmentAndGetsItBack() throws Exception {
    var id = createdId("201", "ROOM");

    mockMvc
        .perform(get(URL + "/" + id).cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id))
        .andExpect(jsonPath("$.code").value("201"))
        .andExpect(jsonPath("$.type").value("ROOM"))
        .andExpect(jsonPath("$.status").value("ACTIVE"));
  }

  @Test
  void theCodeIsStoredWithoutSurroundingSpaces() throws Exception {
    create("  C1  ", "CABIN")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.code").value("C1"));
  }

  @Test
  void aDuplicateCodeIs409OnCreateWithTheCodeField() throws Exception {
    create("201", "ROOM").andExpect(status().isCreated());

    create(" 201", "CABIN")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ENVIRONMENT_CODE_TAKEN"))
        .andExpect(jsonPath("$.field").value("code"));
  }

  @Test
  void invalidCreationDataIs400() throws Exception {
    create("   ", "ROOM")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.field").value("code"));
    create("x".repeat(51), "ROOM").andExpect(status().isBadRequest());
    mockMvc
        .perform(post(URL).cookie(admin()).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            post(URL)
                .cookie(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"201\",\"type\":\"TENT\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void anAdminEditsTheCodeAndTheType() throws Exception {
    var id = createdId("201", "ROOM");

    update(id, "{\"code\":\" 202 \",\"type\":\"CABIN\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("202"))
        .andExpect(jsonPath("$.type").value("CABIN"))
        .andExpect(jsonPath("$.status").value("ACTIVE"));
  }

  /**
   * Spaces, no-break spaces, zero-width and blank letters: none is a visible code (POST and PATCH).
   */
  @Test
  void aCodeWithoutVisibleCharactersIs400OnCreateAndEdit() throws Exception {
    var id = createdId("201", "ROOM");

    for (var invisible : new String[] {"\u2003", "\u00a0", "\u200b", "\u3164", "\u2800", "<b>"}) {
      create(invisible, "ROOM")
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
          .andExpect(jsonPath("$.field").value("code"));
      update(id, "{\"code\":\"" + invisible + "\"}")
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.field").value("code"));
    }
  }

  @Test
  void aCodeWithAccentsAndSeparatorsIsAccepted() throws Exception {
    create("Caba\u00f1a 3-A/1.b_2", "CABIN")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.code").value("Caba\u00f1a 3-A/1.b_2"));
  }

  @Test
  void anUnreadableBodyIs400InTheBffFormat() throws Exception {
    mockMvc
        .perform(
            post(URL)
                .cookie(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"201\",\"type\":\"TENT\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    mockMvc
        .perform(post(URL).cookie(admin()).contentType(MediaType.APPLICATION_JSON).content("{"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void anEditWithOneFieldKeepsTheOther() throws Exception {
    var id = createdId("201", "ROOM");

    update(id, "{\"type\":\"CABIN\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("201"))
        .andExpect(jsonPath("$.type").value("CABIN"));
  }

  @Test
  void anEditThatChangesNothingOrIsBlankIs400() throws Exception {
    var id = createdId("201", "ROOM");

    update(id, "{}").andExpect(status().isBadRequest());
    update(id, "{\"code\":\"  \"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("code"));
  }

  @Test
  void anEditToAnotherEnvironmentsCodeIs409() throws Exception {
    createdId("201", "ROOM");
    var other = createdId("202", "ROOM");

    update(other, "{\"code\":\"201\"}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ENVIRONMENT_CODE_TAKEN"));
  }

  /** Two administrators edit the same environment at once: both win in turn, none gets a 500. */
  @Test
  void simultaneousEditsOfOneEnvironmentNeverFailWithAServerError() throws Exception {
    var id = createdId("201", "ROOM");
    var token = admin();
    var edits = new ArrayList<Callable<Integer>>();
    for (int i = 0; i < 8; i++) {
      var type = i % 2 == 0 ? "CABIN" : "ROOM";
      edits.add(
          () ->
              mockMvc
                  .perform(
                      patch(URL + "/" + id)
                          .cookie(token)
                          .contentType(MediaType.APPLICATION_JSON)
                          .content("{\"type\":\"" + type + "\"}"))
                  .andReturn()
                  .getResponse()
                  .getStatus());
    }

    try (var pool = Executors.newFixedThreadPool(8)) {
      for (var result : pool.invokeAll(edits)) {
        org.assertj.core.api.Assertions.assertThat(result.get()).isEqualTo(200);
      }
    }
  }

  @Test
  void keepingTheOwnCodeOnAnEditIsNotAConflict() throws Exception {
    var id = createdId("201", "ROOM");

    update(id, "{\"code\":\"201\",\"type\":\"CABIN\"}").andExpect(status().isOk());
  }

  @Test
  void anUnknownOrMalformedIdIs404Or400() throws Exception {
    mockMvc
        .perform(get(URL + "/" + UUID.randomUUID()).cookie(admin()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ENVIRONMENT_NOT_FOUND"));
    update(UUID.randomUUID().toString(), "{\"type\":\"ROOM\"}").andExpect(status().isNotFound());
    mockMvc
        .perform(get(URL + "/not-a-uuid").cookie(admin()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.field").value("id"));
  }

  @Test
  void theListingIsOrderedByCodeAndHidesTheInactiveByDefault() throws Exception {
    createdId("202", "ROOM");
    createdId("201", "ROOM");
    var inactive = createdId("100", "CABIN");
    jdbc.update(
        "UPDATE environments SET status = 'INACTIVE' WHERE id = ?", UUID.fromString(inactive));

    mockMvc
        .perform(get(URL).cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].code").value("201"))
        .andExpect(jsonPath("$[1].code").value("202"));
    mockMvc
        .perform(get(URL).param("status", "INACTIVE").cookie(admin()))
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].code").value("100"))
        .andExpect(jsonPath("$[0].status").value("INACTIVE"));
    mockMvc
        .perform(get(URL).param("status", "ALL").cookie(admin()))
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].code").value("100"));
  }

  @Test
  void anUnknownStatusFilterIs400() throws Exception {
    mockMvc
        .perform(get(URL).param("status", "DELETED").cookie(admin()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("status"));
  }

  @Test
  void withoutSessionEveryOperationIs401() throws Exception {
    var id = UUID.randomUUID();
    mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
    mockMvc.perform(get(URL + "/" + id)).andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            post(URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"201\",\"type\":\"ROOM\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_NO_SESSION"));
    mockMvc
        .perform(
            patch(URL + "/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"1\"}"))
        .andExpect(status().isUnauthorized());
  }

  /** RN-08: a guest session is valid but never reaches the administration. */
  @Test
  void aGuestIs403OnEveryOperationAndNothingIsWritten() throws Exception {
    var guest = new Cookie("sessionToken", jwtTokenProvider.issue(guestId.toString(), "GUEST"));
    var id = createdId("201", "ROOM");

    mockMvc
        .perform(get(URL).cookie(guest))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    mockMvc.perform(get(URL + "/" + id).cookie(guest)).andExpect(status().isForbidden());
    mockMvc
        .perform(
            post(URL)
                .cookie(guest)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"999\",\"type\":\"ROOM\"}"))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            patch(URL + "/" + id)
                .cookie(guest)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"999\"}"))
        .andExpect(status().isForbidden());

    mockMvc
        .perform(get(URL).cookie(admin()))
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].code").value("201"));
  }

  private Cookie admin() {
    return new Cookie("sessionToken", jwtTokenProvider.issue(adminId.toString(), "ADMIN"));
  }

  private ResultActions create(String code, String type) throws Exception {
    return mockMvc.perform(
        post(URL)
            .cookie(admin())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"" + code + "\",\"type\":\"" + type + "\"}"));
  }

  private String createdId(String code, String type) throws Exception {
    var body =
        create(code, type)
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return com.jayway.jsonpath.JsonPath.read(body, "$.id");
  }

  private ResultActions update(String id, String json) throws Exception {
    return mockMvc.perform(
        patch(URL + "/" + id)
            .cookie(admin())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json));
  }

  private void insertUser(UUID id, String role, String document) {
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " must_change_password, status) VALUES (?, ?, 'DNI', ?, 'hash', ?, FALSE, 'ACTIVE')",
        id,
        document + "@example.test",
        document,
        role);
  }
}
