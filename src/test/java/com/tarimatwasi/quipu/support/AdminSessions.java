package com.tarimatwasi.quipu.support;

import com.tarimatwasi.quipu.auth.infrastructure.adapter.out.security.AccountJwtAuthenticationConverter;
import com.tarimatwasi.quipu.auth.infrastructure.adapter.out.security.JwtTokenProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * TAR-227: the page cursors are masked with the key of the session, so a test of a paged listing
 * reads it inside a session of an administrator, built as the security chain builds it from its
 * token. The test clears the security context when it ends.
 */
public final class AdminSessions {

  private AdminSessions() {}

  /** An active ADMIN account with no email; the tables must have been cleared before. */
  public static Long insertAdmin(JdbcTemplate jdbc) {
    Long id = TestIds.next();
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " must_change_password, status)"
            + " VALUES (?, NULL, 'DNI', '00000001', 'hash', 'ADMIN', FALSE, 'ACTIVE')",
        id);
    return id;
  }

  /** A new session of that account becomes the one of the current thread. */
  public static void signIn(ApplicationContext context, Long userId) {
    JwtTokenProvider tokens = context.getBean(JwtTokenProvider.class);
    JwtDecoder decoder = context.getBean(JwtDecoder.class);
    AccountJwtAuthenticationConverter sessions =
        context.getBean(AccountJwtAuthenticationConverter.class);
    SecurityContextHolder.getContext()
        .setAuthentication(sessions.convert(decoder.decode(tokens.issue(userId.toString()))));
  }
}
