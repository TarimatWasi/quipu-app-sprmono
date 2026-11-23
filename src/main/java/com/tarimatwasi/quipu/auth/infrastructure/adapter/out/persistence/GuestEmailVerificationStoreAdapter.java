package com.tarimatwasi.quipu.auth.infrastructure.adapter.out.persistence;

import com.tarimatwasi.quipu.auth.application.port.out.GuestEmailVerificationStorePort;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** TAR-349: guest_email_verifications, one row per guest at most. */
@Component
public class GuestEmailVerificationStoreAdapter implements GuestEmailVerificationStorePort {

  private final JdbcClient jdbc;

  public GuestEmailVerificationStoreAdapter(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<PendingEmailVerification> find(Long guestId) {
    return jdbc.sql(
            "SELECT email, code_hash, expires_at, last_sent_at, attempts"
                + " FROM guest_email_verifications WHERE guest_id = :guestId")
        .param("guestId", guestId)
        .query(
            (rs, row) ->
                new PendingEmailVerification(
                    rs.getString("email"),
                    rs.getString("code_hash"),
                    rs.getTimestamp("expires_at").toInstant(),
                    rs.getTimestamp("last_sent_at").toInstant(),
                    rs.getInt("attempts")))
        .optional();
  }

  @Override
  public void replace(
      Long guestId, String email, String codeHash, Instant expiresAt, Instant sentAt) {
    jdbc.sql(
            "INSERT INTO guest_email_verifications"
                + " (guest_id, email, code_hash, expires_at, last_sent_at, attempts)"
                + " VALUES (:guestId, :email, :codeHash, :expiresAt, :sentAt, 0)"
                + " ON CONFLICT (guest_id) DO UPDATE SET email = EXCLUDED.email,"
                + " code_hash = EXCLUDED.code_hash, expires_at = EXCLUDED.expires_at,"
                + " last_sent_at = EXCLUDED.last_sent_at, attempts = 0")
        .param("guestId", guestId)
        .param("email", email)
        .param("codeHash", codeHash)
        .param("expiresAt", Timestamp.from(expiresAt))
        .param("sentAt", Timestamp.from(sentAt))
        .update();
  }

  @Override
  public void addFailedAttempt(Long guestId) {
    jdbc.sql(
            "UPDATE guest_email_verifications SET attempts = attempts + 1"
                + " WHERE guest_id = :guestId AND attempts < 5")
        .param("guestId", guestId)
        .update();
  }

  @Override
  public void delete(Long guestId) {
    jdbc.sql("DELETE FROM guest_email_verifications WHERE guest_id = :guestId")
        .param("guestId", guestId)
        .update();
  }
}
