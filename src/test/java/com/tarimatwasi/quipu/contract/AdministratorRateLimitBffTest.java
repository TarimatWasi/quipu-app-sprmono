package com.tarimatwasi.quipu.contract;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tarimatwasi.quipu.auth.application.port.in.AdministratorLimitReachedException;
import com.tarimatwasi.quipu.auth.application.port.in.CreateAdministratorUseCase;
import com.tarimatwasi.quipu.auth.application.port.in.ManageAdministratorsUseCase;
import com.tarimatwasi.quipu.auth.infrastructure.adapter.out.security.JwtTokenProvider;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestAccessUseCase;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.shared.masking.MaskSession;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.SessionIds;
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
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * RF-22 and TAR-127 through the real security chain: the creations, the deactivations and the
 * reactivations of administrators of one session user are limited per minute, each route with its
 * own counter, after the session is read and before the use case, and the 429 satisfies the pinned
 * contract.
 */
@SpringBootTest(
    properties = {
      "app.cors.allowed-origin=http://localhost:3000",
      "app.rate-limit.administrator-creations-per-minute=1",
      "app.rate-limit.administrator-status-changes-per-minute=1",
      "app.rate-limit.guest-account-email-changes-per-minute=1"
    })
@AutoConfigureMockMvc
@ImportTestcontainers(PostgresContainers.class)
class AdministratorRateLimitBffTest {

  private static final String SPEC =
      Path.of("target/contract/bff.yaml").toAbsolutePath().toString();
  private static final String URL = "/bff/admin/administrators";
  private static final String REAUTHENTICATION = "{\"currentPassword\":\"Actor12345\"}";
  private static final String RATE_LIMITED =
      "{\"code\":\"RATE_LIMITED\",\"message\":\"Demasiados intentos, espera un momento antes de"
          + " volver a intentar\"}";
  private static final String BODY =
      "{\"documentType\":\"DNI\",\"documentNumber\":\"22222222\",\"email\":\"n@example.com\","
          + "\"currentPassword\":\"Creador12345\"}";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired JwtTokenProvider jwtTokenProvider;

  /**
   * TAR-227: every account of a test signs in to this one session, so an id one request shows is
   * valid in the next.
   */
  private final String sessionId = SessionIds.newSessionId();

  @Autowired IdMasker ids;
  @MockitoBean CreateAdministratorUseCase administrators;
  @MockitoBean ManageAdministratorsUseCase management;
  @MockitoBean ManageGuestAccessUseCase guestAccess;

  private final Long adminId = TestIds.next();
  private final Long otherAdminId = TestIds.next();
  private final Long guestUserId = TestIds.next();
  private String target = "";

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
    insertUser(adminId, "ADMIN", "00000001");
    insertUser(otherAdminId, "ADMIN", "00000003");
    insertUser(guestUserId, "GUEST", "00000002");
    when(administrators.create(any())).thenThrow(new AdministratorLimitReachedException());
    target = sessionIds().mask(IdKind.USER, TestIds.next()).toString();
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

  /** TAR-127. */
  @Test
  void theDeactivationOverTheAllowanceGets429WithRetryAfterBeforeTheUseCase() throws Exception {
    change("deactivate", session(adminId)).andExpect(status().isNoContent());

    MvcResult limited =
        change("deactivate", session(adminId))
            .andExpect(status().isTooManyRequests())
            .andExpect(content().json(RATE_LIMITED, JsonCompareMode.STRICT))
            .andExpect(openApi().isValid(SPEC))
            .andReturn();

    assertRetryAfter(limited);
    verify(management, times(1)).deactivate(any());
  }

  /** TAR-127: each route counts apart, and apart from the creations. */
  @Test
  void theReactivationAndTheCreationHaveTheirOwnAllowance() throws Exception {
    change("deactivate", session(adminId)).andExpect(status().isNoContent());
    change("deactivate", session(adminId)).andExpect(status().isTooManyRequests());

    change("reactivate", session(adminId)).andExpect(status().isNoContent());
    MvcResult limited =
        change("reactivate", session(adminId))
            .andExpect(status().isTooManyRequests())
            .andExpect(content().json(RATE_LIMITED, JsonCompareMode.STRICT))
            .andExpect(openApi().isValid(SPEC))
            .andReturn();
    create(session(adminId)).andExpect(status().isConflict());

    assertRetryAfter(limited);
    verify(management, times(1)).deactivate(any());
    verify(management, times(1)).reactivate(any());
    verify(administrators, times(1)).create(any());
  }

  @Test
  void anotherAdministratorKeepsItsOwnAllowanceToDeactivateAndOtherAnswersAreUntouched()
      throws Exception {
    change("deactivate", session(adminId)).andExpect(status().isNoContent());
    change("deactivate", session(adminId)).andExpect(status().isTooManyRequests());
    // The list is not one of these routes.
    mockMvc
        .perform(get("/bff/admin/administrators").cookie(session(adminId)))
        .andExpect(status().isOk());

    change("deactivate", session(otherAdminId)).andExpect(status().isNoContent());
    change("deactivate", null).andExpect(status().isUnauthorized());
    change("deactivate", session(guestUserId)).andExpect(status().isForbidden());
    change("deactivate", session(guestUserId)).andExpect(status().isForbidden());
    verify(management, times(2)).deactivate(any());
  }

  /** TAR-229: the changes of the account email of a guest have their own allowance. */
  @Test
  void theGuestAccountEmailChangeOverTheAllowanceGets429BeforeTheUseCase() throws Exception {
    String guest = sessionIds().mask(IdKind.GUEST, TestIds.next()).toString();
    setGuestEmail(guest, session(adminId)).andExpect(status().isNoContent());

    MvcResult limited =
        setGuestEmail(guest, session(adminId))
            .andExpect(status().isTooManyRequests())
            .andExpect(content().json(RATE_LIMITED, JsonCompareMode.STRICT))
            .andExpect(openApi().isValid(SPEC))
            .andReturn();
    // Another administrator keeps its allowance; the other routes keep theirs.
    setGuestEmail(guest, session(otherAdminId)).andExpect(status().isNoContent());
    change("deactivate", session(adminId)).andExpect(status().isNoContent());
    setGuestEmail(guest, session(guestUserId)).andExpect(status().isForbidden());

    assertRetryAfter(limited);
    verify(guestAccess, times(2)).setAccountEmail(anyLong(), anyString());
  }

  private ResultActions setGuestEmail(String guest, Cookie session) throws Exception {
    return mockMvc.perform(
        put("/bff/admin/guests/" + guest + "/account-email")
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"nueva@example.com\"}"));
  }

  private static void assertRetryAfter(MvcResult limited) {
    String retryAfter = limited.getResponse().getHeader("Retry-After");
    assertThat(retryAfter).isNotNull();
    assertThat(Long.parseLong(retryAfter)).isBetween(1L, 61L);
  }

  private ResultActions change(String action, @Nullable Cookie session) throws Exception {
    var request =
        post(URL + "/" + target + "/" + action)
            .contentType(MediaType.APPLICATION_JSON)
            .content(REAUTHENTICATION);
    if (session != null) {
      request.cookie(session);
    }
    return mockMvc.perform(request);
  }

  private ResultActions create(@Nullable Cookie session) throws Exception {
    var request = post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY);
    if (session != null) {
      request.cookie(session);
    }
    return mockMvc.perform(request);
  }

  private Cookie session(Long id) {
    return new Cookie("sessionToken", jwtTokenProvider.renew(id.toString(), sessionId));
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

  /** The ids as the session of the test shows them. */
  private MaskSession sessionIds() {
    return ids.session(sessionId);
  }
}
