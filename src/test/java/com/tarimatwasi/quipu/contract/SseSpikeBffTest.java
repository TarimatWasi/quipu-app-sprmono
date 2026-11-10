package com.tarimatwasi.quipu.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tarimatwasi.quipu.auth.infrastructure.adapter.out.security.JwtTokenProvider;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.SessionIds;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * TAR-365 spike, temporary: the stream through the real security chain. Not in the contract, so
 * there is no OpenAPI check; removed with the spike.
 */
@SpringBootTest(
    properties = {
      "app.cors.allowed-origin=http://localhost:3000",
      "app.spike.sse.enabled=true",
      "app.spike.sse.interval=20ms",
      "app.spike.sse.max-duration=100ms"
    })
@AutoConfigureMockMvc
@ImportTestcontainers(PostgresContainers.class)
class SseSpikeBffTest {

  private static final String STREAM = "/bff/admin/spike/stream";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired JwtTokenProvider jwtTokenProvider;

  private final String sessionId = SessionIds.newSessionId();
  private final Long adminId = TestIds.next();
  private final Long guestId = TestIds.next();

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
    insertUser(adminId, "00000001", "ADMIN");
    insertUser(guestId, "00000002", "GUEST");
  }

  @AfterEach
  void tearDown() {
    TestTables.clear(jdbc);
  }

  @Test
  void anAdminGetsHelloTicksAndByeUntilTheMaximumDuration() throws Exception {
    var response =
        mockMvc
            .perform(get(STREAM).cookie(session(adminId)))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-cache, no-transform"))
            .andExpect(header().string("X-Accel-Buffering", "no"))
            .andReturn()
            .getResponse();

    assertThat(response.getContentType()).startsWith("text/event-stream");
    String body = response.getContentAsString(StandardCharsets.UTF_8);
    assertThat(body)
        .startsWith("event: hello\ndata: {\"openedAt\":")
        .contains("event: tick\ndata: {\"seq\":1,")
        .endsWith("event: bye\ndata: {\"reason\":\"max-duration\"}\n\n");
  }

  @Test
  void withoutASessionTheStreamIsRefused() throws Exception {
    mockMvc.perform(get(STREAM)).andExpect(status().isUnauthorized());
  }

  @Test
  void aGuestCannotOpenTheStream() throws Exception {
    mockMvc.perform(get(STREAM).cookie(session(guestId))).andExpect(status().isForbidden());
  }

  private Cookie session(Long id) {
    return new Cookie("sessionToken", jwtTokenProvider.renew(id.toString(), sessionId));
  }

  private void insertUser(Long id, String document, String role) {
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " must_change_password, status) VALUES (?, ?, 'DNI', ?, 'hash', ?, FALSE,"
            + " 'ACTIVE')",
        id,
        document + "@example.test",
        document,
        role);
  }
}
