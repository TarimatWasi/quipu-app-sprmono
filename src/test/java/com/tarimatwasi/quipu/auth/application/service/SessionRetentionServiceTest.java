package com.tarimatwasi.quipu.auth.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;

/** TAR-228: the sessions no token can name any more are removed, with their refresh tokens. */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@ImportTestcontainers(PostgresContainers.class)
class SessionRetentionServiceTest {

  @Autowired SessionRetentionService retention;
  @Autowired JdbcTemplate jdbc;

  @Value("${app.jwt.expiration}")
  Duration accessToken;

  @Value("${app.changes.max-duration}")
  Duration stream;

  private final Long userId = TestIds.next();

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " must_change_password, status, failed_login_attempts)"
            + " VALUES (?, 'a@example.test', 'DNI', '00000001', 'h', 'ADMIN', FALSE, 'ACTIVE', 0)",
        userId);
  }

  /**
   * The removal is safe only because a missing session is never mistaken for an open one while a
   * token could still name it: the margin outlives every token of a session.
   */
  @Test
  void theMarginOutlivesEveryTokenOfASession() {
    assertThat(SessionRetentionService.GRACE).isGreaterThan(accessToken).isGreaterThan(stream);
  }

  @Test
  void onlyTheSessionsExpiredLongerThanTheMarginGoAndTheirTokensWithThem() {
    Instant now = Instant.now();
    Long old = session(now.minus(Duration.ofDays(3)), null);
    Long revokedLongAgo = session(now.minus(Duration.ofDays(2)), now.minus(Duration.ofDays(20)));
    Long justExpired = session(now.minus(Duration.ofHours(1)), null);
    Long revokedButNotExpired =
        session(now.plus(Duration.ofDays(10)), now.minus(Duration.ofDays(5)));
    Long open = session(now.plus(Duration.ofDays(29)), null);
    token(old);
    token(old);
    token(open);

    assertThat(retention.purgeEnded()).isEqualTo(2);

    assertThat(jdbc.queryForList("SELECT id FROM sessions ORDER BY id", Long.class))
        .containsExactlyInAnyOrder(justExpired, revokedButNotExpired, open)
        .doesNotContain(old, revokedLongAgo);
    assertThat(jdbc.queryForList("SELECT session_id FROM refresh_tokens", Long.class))
        .containsExactly(open);
  }

  @Test
  void aCallRemovesAtMostABatchTheOldestFirst() {
    Instant now = Instant.now();
    for (int i = 0; i < SessionRetentionService.BATCH + 1; i++) {
      session(now.minus(Duration.ofDays(2)).minusSeconds(i), null);
    }
    Long newest = session(now.minus(Duration.ofDays(2)).plusSeconds(1), null);

    assertThat(retention.purgeEnded()).isEqualTo(SessionRetentionService.BATCH);
    List<@Nullable Long> left = jdbc.queryForList("SELECT id FROM sessions", Long.class);
    assertThat(left).hasSize(2).contains(newest);
    assertThat(retention.purgeEnded()).isEqualTo(2);
  }

  private Long session(Instant absoluteExpiresAt, @Nullable Instant revokedAt) {
    Long id = TestIds.next();
    jdbc.update(
        "INSERT INTO sessions (id, user_id, sid_hash, security_version, temporary,"
            + " absolute_expires_at, last_used_at, revoked_at, revoked_reason)"
            + " VALUES (?, ?, ?, 0, FALSE, ?, ?, ?, ?)",
        id,
        userId,
        String.format("%064d", id),
        Timestamp.from(absoluteExpiresAt),
        Timestamp.from(absoluteExpiresAt.minus(Duration.ofDays(1))),
        revokedAt == null ? null : Timestamp.from(revokedAt),
        revokedAt == null ? null : "LOGOUT");
    return id;
  }

  private void token(Long sessionId) {
    Long id = TestIds.next();
    jdbc.update(
        "INSERT INTO refresh_tokens (id, session_id, token_hash, issued_at)"
            + " VALUES (?, ?, ?, now())",
        id,
        sessionId,
        String.format("%064d", id));
  }
}
