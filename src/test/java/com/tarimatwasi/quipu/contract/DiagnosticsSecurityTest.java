package com.tarimatwasi.quipu.contract;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tarimatwasi.quipu.auth.adapter.out.security.JwtTokenProvider;
import com.tarimatwasi.quipu.shared.adapter.out.email.ResendPingService;
import com.tarimatwasi.quipu.shared.adapter.out.storage.R2PingService;
import com.tarimatwasi.quipu.support.PostgresContainers;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** /bff/diagnostics/** touches R2 and Resend: only an authenticated ADMIN (sessionToken cookie). */
@SpringBootTest
@ImportTestcontainers(PostgresContainers.class)
class DiagnosticsSecurityTest {

  private static final String URL = "/bff/diagnostics/ping-services";

  @Autowired WebApplicationContext webApplicationContext;
  @Autowired FilterChainProxy springSecurityFilterChain;
  @Autowired JwtTokenProvider jwtTokenProvider;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean R2PingService r2PingService;
  @MockitoBean ResendPingService resendPingService;
  MockMvc mockMvc;
  private final UUID adminId = UUID.randomUUID();
  private final UUID guestId = UUID.randomUUID();

  /** The session is only as good as its account (TAR-125): both exist, this test owns the users. */
  @BeforeEach
  void setUp() {
    jdbc.update("DELETE FROM users");
    insertUser(adminId, "ADMIN", "00000001");
    insertUser(guestId, "GUEST", "00000002");
    when(r2PingService.ping()).thenReturn("OK");
    when(resendPingService.ping()).thenReturn("OK");
    mockMvc =
        MockMvcBuilders.webAppContextSetup(webApplicationContext)
            .addFilters(springSecurityFilterChain)
            .build();
  }

  @Test
  void rejectsRequestWithoutSession() throws Exception {
    mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
  }

  @Test
  void rejectsGuestSession() throws Exception {
    var token = jwtTokenProvider.issue(guestId.toString(), "GUEST");

    mockMvc
        .perform(get(URL).cookie(new Cookie("sessionToken", token)))
        .andExpect(status().isForbidden());
  }

  @Test
  void rejectsInvalidToken() throws Exception {
    mockMvc
        .perform(get(URL).cookie(new Cookie("sessionToken", "garbage")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void allowsAdminSession() throws Exception {
    var token = jwtTokenProvider.issue(adminId.toString(), "ADMIN");

    mockMvc.perform(get(URL).cookie(new Cookie("sessionToken", token))).andExpect(status().isOk());
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
