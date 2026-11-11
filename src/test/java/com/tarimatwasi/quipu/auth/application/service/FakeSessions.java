package com.tarimatwasi.quipu.auth.application.service;

import com.tarimatwasi.quipu.auth.application.port.out.SessionTokenPort;
import com.tarimatwasi.quipu.auth.application.port.out.UserRepositoryPort;
import com.tarimatwasi.quipu.support.InMemorySessionStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.UnaryOperator;

/** TAR-228: fake session tokens that name what they carry, and real sessions stored in memory. */
final class FakeSessions {

  private FakeSessions() {}

  /** Tokens whose value tells the test how they were issued. */
  static SessionTokenPort tokens(UnaryOperator<String> issue) {
    return new SessionTokenPort() {
      @Override
      public String issue(String userId) {
        return issue.apply(userId);
      }

      @Override
      public String renew(String userId, String sessionId) {
        return "renewed:" + sessionId + ":" + issue.apply(userId);
      }

      @Override
      public String issueTemporary(
          String userId, String sessionId, Long grantId, Instant expiresAt) {
        return "temporary:" + grantId + ":" + expiresAt + ":" + issue.apply(userId);
      }

      @Override
      public Optional<String> sessionIdOf(String accessToken) {
        return Optional.empty();
      }
    };
  }

  /** The real sessions over those tokens, with the lifetimes decided on 2026-11-09. */
  static SessionService over(SessionTokenPort tokens, UserRepositoryPort users, Clock clock) {
    return new SessionService(
        new InMemorySessionStore(),
        tokens,
        users,
        new SessionPolicy(Duration.ofDays(7), Duration.ofDays(30), Duration.ofSeconds(30)),
        clock,
        org.mockito.Mockito.mock(com.tarimatwasi.quipu.shared.changes.ChangeSignals.class));
  }
}
