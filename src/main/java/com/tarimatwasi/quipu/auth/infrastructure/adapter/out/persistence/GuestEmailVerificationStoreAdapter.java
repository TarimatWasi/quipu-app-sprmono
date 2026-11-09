package com.tarimatwasi.quipu.auth.infrastructure.adapter.out.persistence;

import com.tarimatwasi.quipu.auth.application.port.out.GuestEmailVerificationStorePort;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** TAR-349: guest_email_verifications, one row per guest at most. */
@Component
public class GuestEmailVerificationStoreAdapter implements GuestEmailVerificationStorePort {

  private final JdbcTemplate jdbc;

  public GuestEmailVerificationStoreAdapter(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<PendingEmailVerification> find(Long guestId) {
    return jdbc
        .query(
            "SELECT email, code_hash, expires_at, last_sent_at, attempts"
                + " FROM guest_email_verifications WHERE guest_id = ?",
            (rs, row) ->
                new PendingEmailVerification(
                    rs.getString("email"),
                    rs.getString("code_hash"),
                    rs.getTimestamp("expires_at").toInstant(),
                    rs.getTimestamp("last_sent_at").toInstant(),
                    rs.getInt("attempts")),
            guestId)
        .stream()
        .findFirst();
  }

  @Override
  public void replace(
      Long guestId, String email, String codeHash, Instant expiresAt, Instant sentAt) {
    jdbc.update(
        "INSERT INTO guest_email_verifications"
            + " (guest_id, email, code_hash, expires_at, last_sent_at, attempts)"
            + " VALUES (?, ?, ?, ?, ?, 0)"
            + " ON CONFLICT (guest_id) DO UPDATE SET email = EXCLUDED.email,"
            + " code_hash = EXCLUDED.code_hash, expires_at = EXCLUDED.expires_at,"
            + " last_sent_at = EXCLUDED.last_sent_at, attempts = 0",
        guestId,
        email,
        codeHash,
        Timestamp.from(expiresAt),
        Timestamp.from(sentAt));
  }

  @Override
  public void addFailedAttempt(Long guestId) {
    jdbc.update(
        "UPDATE guest_email_verifications SET attempts = attempts + 1"
            + " WHERE guest_id = ? AND attempts < 5",
        guestId);
  }

  @Override
  public void delete(Long guestId) {
    jdbc.update("DELETE FROM guest_email_verifications WHERE guest_id = ?", guestId);
  }
}
