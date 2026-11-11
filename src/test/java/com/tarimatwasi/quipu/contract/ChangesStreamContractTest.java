package com.tarimatwasi.quipu.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tarimatwasi.quipu.auth.application.port.out.SessionStorePort;
import com.tarimatwasi.quipu.auth.application.port.out.UserRepositoryPort;
import com.tarimatwasi.quipu.auth.infrastructure.adapter.out.security.JwtTokenProvider;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.support.ChangeStreamRegistry;
import com.tarimatwasi.quipu.shared.changes.ChangeSignals;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.SessionIds;
import com.tarimatwasi.quipu.support.SseFrames;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    properties = {
      "app.cors.allowed-origin=http://localhost:3000",
      "app.changes.heartbeat=10ms",
      "app.changes.max-duration=55ms"
    })
@AutoConfigureMockMvc
@ImportTestcontainers(PostgresContainers.class)
class ChangesStreamContractTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired JwtTokenProvider tokens;
  @Autowired IdMasker ids;
  @Autowired ChangeStreamRegistry streams;
  @Autowired PlatformTransactionManager transactions;
  @MockitoSpyBean UserRepositoryPort users;
  @MockitoSpyBean SessionStorePort sessions;
  private final Long admin = TestIds.next();
  private final Long guest = TestIds.next();
  private final Long guestAccount = TestIds.next();
  private final String sid = SessionIds.newSessionId();

  @BeforeEach
  void setup() {
    TestTables.clear(jdbc);
    jdbc.update(
        "INSERT INTO guests (id,document_type,document_number,guest_type,status)"
            + " VALUES (?, 'DNI','00000002','TEMPORARY','ACTIVE')",
        guest);
    jdbc.update(
        "INSERT INTO users (id,document_type,document_number,password_hash,role,"
            + "must_change_password,status) VALUES (?, 'DNI','00000001','hash','ADMIN',FALSE,'ACTIVE')",
        admin);
    jdbc.update(
        "INSERT INTO users (id,guest_id,document_type,document_number,password_hash,role,"
            + "must_change_password,status) VALUES (?, ?, 'DNI','00000002','hash','GUEST',FALSE,'ACTIVE')",
        guestAccount,
        guest);
  }

  @AfterEach
  void cleanup() {
    assertThat(streams.connectionCount()).isZero();
    TestTables.clear(jdbc);
  }

  private Cookie session(Long account) {
    return new Cookie("sessionToken", tokens.renew(account.toString(), sid));
  }

  @Test
  void stream_adminIdle_usesRealSecurityWithoutPeriodicDatabaseReads() throws Exception {
    var cookie = session(admin);
    clearInvocations(users, sessions);
    String body =
        mvc.perform(get("/bff/admin/changes/stream").cookie(cookie).accept("text/event-stream"))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store, no-transform"))
            .andExpect(header().string("X-Accel-Buffering", "no"))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    assertThat(body)
        .startsWith("event: ready")
        .contains(": heartbeat")
        .endsWith("event: closing\ndata: {\"reason\":\"max-duration\"}\n\n");
    verify(users, times(2)).findById(admin);
    verify(sessions, times(2))
        .isClosed(ArgumentMatchers.anyString(), ArgumentMatchers.any(Instant.class));
    SseFrames.assertValid(body, "Admin");
  }

  @Test
  void stream_temporaryGuest_acceptsOwnRoleOnly() throws Exception {
    var cookie = session(guestAccount);
    mvc.perform(get("/bff/guest/changes/stream").cookie(cookie)).andExpect(status().isOk());
    mvc.perform(get("/bff/admin/changes/stream").cookie(cookie)).andExpect(status().isForbidden());
    mvc.perform(get("/bff/guest/changes/stream").cookie(session(admin)))
        .andExpect(status().isForbidden());
  }

  @Test
  void stream_missingPendingOrDisabledCredentials_rejectsBeforeOpening() throws Exception {
    mvc.perform(get("/bff/admin/changes/stream")).andExpect(status().isUnauthorized());
    var cookie = session(admin);
    jdbc.update("UPDATE users SET must_change_password=TRUE WHERE id=?", admin);
    mvc.perform(get("/bff/admin/changes/stream").cookie(cookie)).andExpect(status().isForbidden());
    jdbc.update("UPDATE users SET must_change_password=FALSE,status='INACTIVE' WHERE id=?", admin);
    mvc.perform(get("/bff/admin/changes/stream").cookie(cookie))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void stream_lockedAccount_returnsJsonUnauthorized() throws Exception {
    var cookie = session(admin);
    jdbc.update("UPDATE users SET locked_until=now()+interval '10 minutes' WHERE id=?", admin);
    mvc.perform(get("/bff/admin/changes/stream").cookie(cookie).accept("text/event-stream"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_NO_SESSION"));
  }

  @Test
  void stream_limitsAndOrigin_returns429Or403() throws Exception {
    var cookie = session(admin);
    try (var one =
            streams.reserve(
                admin, "one", 0, true, ids.session(sid), Instant.now().plusSeconds(60));
        var two =
            streams.reserve(
                admin, "two", 0, true, ids.session(sid), Instant.now().plusSeconds(60));
        var three =
            streams.reserve(
                admin, "three", 0, true, ids.session(sid), Instant.now().plusSeconds(60));
        var four =
            streams.reserve(
                admin, "four", 0, true, ids.session(sid), Instant.now().plusSeconds(60))) {
      one.activate(null);
      two.activate(null);
      three.activate(null);
      four.activate(null);
      mvc.perform(get("/bff/admin/changes/stream").cookie(cookie).accept("text/event-stream"))
          .andExpect(status().isTooManyRequests())
          .andExpect(header().string(HttpHeaders.RETRY_AFTER, "30"))
          .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }
    mvc.perform(
            get("/bff/admin/changes/stream")
                .cookie(cookie)
                .header(HttpHeaders.ORIGIN, "https://foreign.invalid"))
        .andExpect(status().isForbidden());
  }

  @Test
  void changed_deliveryDefect_preservesCommittedBusinessResult() {
    try (var listener =
        streams.reserve(
            guestAccount, "test", 0, false, ids.session(sid), Instant.now().plusSeconds(60))) {
      listener.activate(guest);
      var brokenSignal = Mockito.mock(ChangeSignals.Change.class);
      new TransactionTemplate(transactions)
          .executeWithoutResult(
              tx -> {
                jdbc.update("UPDATE guests SET document_number='00000003' WHERE id=?", guest);
                streams.changed(brokenSignal);
              });
      assertThat(
              jdbc.queryForObject(
                  "SELECT document_number FROM guests WHERE id=?", String.class, guest))
          .isEqualTo("00000003");
    }
  }

  @Test
  void stream_passwordResetAndDeactivation_closesAfterCommit() throws Exception {
    try (var listener =
        streams.reserve(
            admin, "test-sid", 0, true, ids.session(sid), Instant.now().plusSeconds(60))) {
      listener.activate(null);
      new TransactionTemplate(transactions)
          .executeWithoutResult(tx -> users.resetPassword(admin, "new-hash", Instant.now()));
      assertThat(listener.next()).contains("session-revoked");
    }
    try (var listener =
        streams.reserve(
            guestAccount, "test-sid", 0, false, ids.session(sid), Instant.now().plusSeconds(60))) {
      listener.activate(guest);
      new TransactionTemplate(transactions)
          .executeWithoutResult(tx -> users.setGuestAccountStatus(guest, "INACTIVE"));
      assertThat(listener.next()).contains("session-revoked");
    }
  }
}
