package com.tarimatwasi.quipu.auth.adapter.out.security;

import com.tarimatwasi.quipu.auth.domain.UserAccount;
import com.tarimatwasi.quipu.auth.port.out.SessionTokenPort;
import com.tarimatwasi.quipu.auth.port.out.UserRepositoryPort;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

@Component
public class JwtTokenProvider implements SessionTokenPort {

  /**
   * Still written for the clients that read it, but the account decides: the claim is no longer
   * trusted (TAR-125).
   */
  private static final String PASSWORD_CHANGE_PENDING_CLAIM = "mcp";

  private final SecretKey key;
  private final Duration expiration;
  private final Clock clock;
  private final UserRepositoryPort users;

  public JwtTokenProvider(JwtProperties properties, Clock clock, UserRepositoryPort users) {
    this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    this.expiration = properties.expiration();
    this.clock = clock;
    this.users = users;
  }

  public String issue(String userId, String role) {
    return issue(userId, role, false);
  }

  @Override
  public String issue(String userId, String role, boolean mustChangePassword) {
    Instant now = clock.instant();
    return Jwts.builder()
        .subject(userId)
        .claim("role", role)
        .claim(PASSWORD_CHANGE_PENDING_CLAIM, mustChangePassword)
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plus(expiration)))
        .signWith(key, Jwts.SIG.HS256)
        .compact();
  }

  public record Session(String userId, String role, boolean mustChangePassword) {}

  /**
   * Empty if the token is malformed, tampered with or expired, or if its account no longer exists,
   * is disabled or changed its password after the token was issued (TAR-125). The account is read
   * on every call, so the role and the pending password change come from it and not from the token:
   * a closed account, a changed password or a new role take effect at once.
   */
  public Optional<Session> parse(String token) {
    Claims claims;
    try {
      claims =
          Jwts.parser()
              .verifyWith(key)
              .clock(() -> Date.from(clock.instant()))
              .build()
              .parseSignedClaims(token)
              .getPayload();
    } catch (JwtException | IllegalArgumentException e) {
      return Optional.empty();
    }
    String userId = claims.getSubject();
    String role = claims.get("role", String.class);
    Date issuedAt = claims.getIssuedAt();
    if (userId == null || userId.isBlank() || role == null || role.isBlank() || issuedAt == null) {
      return Optional.empty();
    }
    return activeAccount(userId)
        .filter(account -> !account.issuedBeforePasswordChange(issuedAt.toInstant()))
        .map(account -> new Session(userId, account.role().name(), account.mustChangePassword()));
  }

  private Optional<UserAccount> activeAccount(String userId) {
    long id;
    try {
      id = Long.parseLong(userId);
    } catch (IllegalArgumentException notAnId) {
      return Optional.empty();
    }
    return users.findById(id).filter(account -> !account.isDisabled());
  }
}
