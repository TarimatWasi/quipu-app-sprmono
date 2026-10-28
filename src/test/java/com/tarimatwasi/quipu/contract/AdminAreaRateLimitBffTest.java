package com.tarimatwasi.quipu.contract;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tarimatwasi.quipu.auth.application.port.in.AdministratorLimitReachedException;
import com.tarimatwasi.quipu.auth.application.port.in.CreateAdministratorUseCase;
import com.tarimatwasi.quipu.auth.infrastructure.adapter.out.security.JwtTokenProvider;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import jakarta.servlet.http.Cookie;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * TAR-221 through the real security chain: every request of one ADMIN session to /bff/admin/**,
 * whatever the route and the method, shares one allowance per minute; other users and the routes
 * outside the area keep theirs.
 */
@SpringBootTest(
    properties = {
      "app.cors.allowed-origin=http://localhost:3000",
      "app.rate-limit.admin-requests-per-minute=3",
      "app.rate-limit.administrator-creations-per-minute=1"
    })
@AutoConfigureMockMvc
@ImportTestcontainers(PostgresContainers.class)
class AdminAreaRateLimitBffTest {

  private static final String SPEC =
      Path.of("target/contract/bff.yaml").toAbsolutePath().toString();
  private static final String EXPENSES = "/bff/admin/expenses";
  private static final String FINES = "/bff/admin/fines";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired JwtTokenProvider jwtTokenProvider;
  @MockitoBean CreateAdministratorUseCase administrators;

  private final Long adminId = TestIds.next();
  private final Long otherAdminId = TestIds.next();

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
    insertUser(adminId, "00000001");
    insertUser(otherAdminId, "00000003");
    when(administrators.create(any())).thenThrow(new AdministratorLimitReachedException());
  }

  @AfterEach
  void tearDown() {
    TestTables.clear(jdbc);
  }

  @Test
  void overTheAllowanceAnyAdminRouteGets429WithRetryAfter() throws Exception {
    listExpenses(adminId).andExpect(status().isOk());
    createFine(adminId).andExpect(status().isBadRequest());
    listExpenses(adminId).andExpect(status().isOk());

    assertLimited(listExpenses(adminId).andExpect(openApi().isValid(SPEC)));
    assertLimited(createFine(adminId));
  }

  @Test
  void anotherAdministratorKeepsItsOwnAllowance() throws Exception {
    for (int i = 0; i < 3; i++) {
      listExpenses(adminId).andExpect(status().isOk());
    }
    assertLimited(listExpenses(adminId));

    listExpenses(otherAdminId).andExpect(status().isOk());
    createFine(otherAdminId)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void aRouteOutsideTheAdminAreaDoesNotSpendTheAllowance() throws Exception {
    for (int i = 0; i < 5; i++) {
      mockMvc.perform(get("/bff/auth/me").cookie(session(adminId))).andExpect(status().isOk());
    }

    for (int i = 0; i < 3; i++) {
      listExpenses(adminId).andExpect(status().isOk());
    }
    assertLimited(listExpenses(adminId));
  }

  /** A specific limit still holds while the general allowance has room left. */
  @Test
  void theLimitOfTheCreationsOfAdministratorsStillHolds() throws Exception {
    createAdministrator(adminId).andExpect(status().isConflict());
    assertLimited(createAdministrator(adminId));
    verify(administrators, times(1)).create(any());

    // Both requests counted in the general allowance too: one left of three.
    listExpenses(adminId).andExpect(status().isOk());
    assertLimited(listExpenses(adminId));
  }

  private static void assertLimited(ResultActions request) throws Exception {
    var response =
        request
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
            .andReturn()
            .getResponse();
    String retryAfter = response.getHeader("Retry-After");
    assertThat(retryAfter).isNotNull();
    assertThat(Long.parseLong(retryAfter)).isBetween(1L, 61L);
  }

  private ResultActions listExpenses(Long user) throws Exception {
    return mockMvc.perform(get(EXPENSES).param("month", "2026-09").cookie(session(user)));
  }

  /** A body the schema refuses: a deterministic 400 when the request gets through. */
  private ResultActions createFine(Long user) throws Exception {
    return mockMvc.perform(
        post(FINES).cookie(session(user)).contentType(MediaType.APPLICATION_JSON).content("{}"));
  }

  private ResultActions createAdministrator(Long user) throws Exception {
    return mockMvc.perform(
        post("/bff/admin/administrators")
            .cookie(session(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"documentType\":\"DNI\",\"documentNumber\":\"22222222\","
                    + "\"email\":\"n@example.com\",\"currentPassword\":\"Creador12345\"}"));
  }

  private Cookie session(Long id) {
    return new Cookie("sessionToken", jwtTokenProvider.issue(id.toString()));
  }

  private void insertUser(Long id, String document) {
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " must_change_password, status) VALUES (?, ?, 'DNI', ?, 'hash', 'ADMIN', FALSE,"
            + " 'ACTIVE')",
        id,
        document + "@example.test",
        document);
  }
}
