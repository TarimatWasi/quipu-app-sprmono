package com.tarimatwasi.quipu.audit.infrastructure.adapter.out.persistence;

import com.tarimatwasi.quipu.audit.application.port.out.AuditTrailPort;
import com.tarimatwasi.quipu.audit.domain.AuditRecord;
import java.sql.Timestamp;
import org.springframework.jdbc.core.simple.JdbcClient;
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
      VALUES (:id, :specVersion, :source, :type, :subject, :occurredAt, :actorUserId, :guestId,
              CAST(:data AS jsonb))
      ON CONFLICT (id) DO NOTHING
      """;

  private final JdbcClient jdbc;
  private final JsonMapper json;

  public AuditTrailJdbcAdapter(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Override
  public boolean append(AuditRecord entry) {
    ObjectNode data = json.valueToTree(entry.event());
    data.remove("metadata");
    return jdbc.sql(INSERT)
            .param("id", entry.id())
            .param("specVersion", AuditRecord.SPEC_VERSION)
            .param("source", entry.source())
            .param("type", entry.type())
            .param("subject", entry.subject())
            .param("occurredAt", Timestamp.from(entry.time()))
            .param("actorUserId", entry.actorUserId())
            .param("guestId", entry.guestId())
            .param("data", json.writeValueAsString(data))
            .update()
        == 1;
  }
}
