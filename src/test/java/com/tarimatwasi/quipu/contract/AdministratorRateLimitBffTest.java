package com.tarimatwasi.quipu.contract;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tarimatwasi.quipu.auth.adapter.out.security.JwtTokenProvider;
import com.tarimatwasi.quipu.auth.port.in.AdministratorLimitReachedException;
import com.tarimatwasi.quipu.auth.port.in.CreateAdministratorUseCase;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import jakarta.servlet.http.Cookie;
import java.nio.file.Path;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * RF-22 through the real security chain: the creations of administrators of one session user are
 * limited per minute, after the session is read and before the use case, and the 429 satisfies the
 * pinned contract.
 */
@SpringBootTest(
    properties = {
      "app.cors.allowed-origin=http://localhost:3000",
      "app.rate-limit.administrator-creations-per-minute=1"
    })
@AutoConfigureMockMvc
@ImportTestcontainers(PostgresContainers.class)
class AdministratorRateLimitBffTest {

  private static final String SPEC =
      Path.of("target/contract/bff.yaml").toAbsolutePath().toString();
  private static final String URL = "/bff/admin/administrators";
  private static final String BODY =
      "{\"documentType\":\"DNI\",\"documentNumber\":\"22222222\",\"email\":\"n@example.com\","
          + "\"currentPassword\":\"Creador12345\"}";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired JwtTokenProvider jwtTokenProvider;
  @MockitoBean CreateAdministratorUseCase administrators;

  private final Long adminId = TestIds.next();
  private final Long otherAdminId = TestIds.next();
  private final Long guestUserId = TestIds.next();

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
    insertUser(adminId, "ADMIN", "00000001");
    insertUser(otherAdminId, "ADMIN", "00000003");
    insertUser(guestUserId, "GUEST", "00000002");
    when(administrators.create(any())).thenThrow(new AdministratorLimitReachedException());
  }

  @AfterEach
  void tearDown() {
    TestTables.clear(jdbc);
  }

  @Test
  void theCreationOverTheAllowanceGets429WithRetryAfterBeforeTheUseCase() throws Exception {
    create(session(adminId)).andExpect(status().isConflict());

    MvcResult limited =
        create(session(adminId))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
            .andExpect(openApi().isValid(SPEC))
            .andReturn();

    String retryAfter = limited.getResponse().getHeader("Retry-After");
    assertThat(retryAfter).isNotNull();
    assertThat(Long.parseLong(retryAfter)).isBetween(1L, 61L);
    verify(administrators, times(1)).create(any());
  }

  @Test
  void anotherAdministratorKeepsItsOwnAllowanceAndOtherAnswersAreUntouched() throws Exception {
    create(session(adminId)).andExpect(status().isConflict());
    create(session(adminId)).andExpect(status().isTooManyRequests());

    create(session(otherAdminId)).andExpect(status().isConflict());
    create(null)
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_NO_SESSION"));
    create(session(guestUserId))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    create(session(guestUserId))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    verify(administrators, times(2)).create(any());
  }

  private ResultActions create(@Nullable Cookie session) throws Exception {
    var request = post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY);
    if (session != null) {
      request.cookie(session);
    }
    return mockMvc.perform(request);
  }

  private Cookie session(Long id) {
    return new Cookie("sessionToken", jwtTokenProvider.issue(id.toString()));
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
