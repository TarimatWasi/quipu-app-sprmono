package com.tarimatwasi.quipu.audit.infrastructure.adapter.out.persistence;

import com.tarimatwasi.quipu.audit.application.port.out.AuditTrailPort;
import com.tarimatwasi.quipu.audit.domain.AuditRecord;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Appends to audit_event; the unique event id turns a redelivery into a no-op. */
@Component
public class AuditTrailJdbcAdapter implements AuditTrailPort {
  private static final String INSERT =
      """
      INSERT INTO audit_event (id, spec_version, source, type, subject, occurred_at,
                               actor_user_id, guest_id, data)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
      ON CONFLICT (id) DO NOTHING
      """;

  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  public AuditTrailJdbcAdapter(JdbcTemplate jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Override
  public boolean append(AuditRecord entry) {
    ObjectNode data = json.valueToTree(entry.event());
    data.remove("metadata");
    return jdbc.update(
            INSERT,
            entry.id(),
            AuditRecord.SPEC_VERSION,
            entry.source(),
            entry.type(),
            entry.subject(),
            Timestamp.from(entry.time()),
            entry.actorUserId(),
            entry.guestId(),
            json.writeValueAsString(data))
        == 1;
  }
}
