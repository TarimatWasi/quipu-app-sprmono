package com.tarimatwasi.quipu.notification.infrastructure.adapter.out.persistence;

import com.tarimatwasi.quipu.notification.application.port.out.NotificationRepositoryPort;
import com.tarimatwasi.quipu.notification.domain.Audience;
import com.tarimatwasi.quipu.notification.domain.Notification;
import com.tarimatwasi.quipu.notification.domain.NotificationType;
import com.tarimatwasi.quipu.notification.domain.TargetKind;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.InvalidCursorException;
import com.tarimatwasi.quipu.shared.paging.MaskedCursor;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * notifications and notification_reads. An inbox is the shared one of the administrators or the one
 * of a guest; the read state is per account. Newest first, by (created_at, id).
 */
@Component
public class NotificationJdbcAdapter implements NotificationRepositoryPort {
  private static final TypeReference<Map<String, String>> FACTS = new TypeReference<>() {};

  private final JdbcClient jdbc;
  private final JsonMapper json;
  private final IdMasker masker;

  public NotificationJdbcAdapter(JdbcClient jdbc, JsonMapper json, IdMasker masker) {
    this.jdbc = jdbc;
    this.json = json;
    this.masker = masker;
  }

  @Override
  public Optional<Long> insert(Notification notification) {
    return jdbc.sql(
            """
            INSERT INTO notifications (event_id, type, audience, guest_id, target_kind, target_id,
                                       facts, created_at)
            VALUES (:eventId, :type, :audience, :guestId, :targetKind, :targetId,
                    CAST(:facts AS jsonb), :createdAt)
            ON CONFLICT (event_id, type) DO NOTHING
            RETURNING id
            """)
        .param("eventId", notification.eventId())
        .param("type", notification.type().name())
        .param("audience", notification.audience().name())
        .param("guestId", notification.guestId())
        .param("targetKind", notification.targetKind().name())
        .param("targetId", notification.targetId())
        .param("facts", json.writeValueAsString(notification.facts()))
        .param("createdAt", Timestamp.from(notification.createdAt()))
        .query(Long.class)
        .optional();
  }

  @Override
  public CursorPage<Notification> find(Inbox inbox, boolean unreadOnly, PageQuery page) {
    Map<String, @Nullable Object> params = inboxParams(inbox);
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT n.*, r.read_at FROM notifications n
            LEFT JOIN notification_reads r ON r.notification_id = n.id AND r.user_id = :userId
            """);
    sql.append(inboxFilter(inbox));
    if (unreadOnly) {
      sql.append(" AND r.read_at IS NULL");
    }
    if (page.after() != null) {
      Key after = keyOf(inbox, page.after());
      sql.append(" AND (n.created_at, n.id) < (:afterCreatedAt, :afterId)");
      params.put("afterCreatedAt", Timestamp.from(after.createdAt()));
      params.put("afterId", after.id());
    }
    sql.append(" ORDER BY n.created_at DESC, n.id DESC LIMIT :limit");
    // One more than the limit: the extra row only says that there is a next page.
    params.put("limit", page.limit() + 1);
    List<Notification> rows =
        jdbc.sql(sql.toString()).params(params).query(this::notification).list();
    return CursorPage.of(
        rows,
        page.limit(),
        row -> MaskedCursor.of(masker, IdKind.NOTIFICATION, Objects.requireNonNull(row.id())));
  }

  @Override
  public long unreadCount(Inbox inbox) {
    return jdbc.sql(
            """
            SELECT count(*) FROM notifications n
            LEFT JOIN notification_reads r ON r.notification_id = n.id AND r.user_id = :userId
            """
                + inboxFilter(inbox)
                + " AND r.read_at IS NULL")
        .params(inboxParams(inbox))
        .query(Long.class)
        .single();
  }

  @Override
  public boolean markRead(Inbox inbox, Long id, Instant at) {
    Map<String, @Nullable Object> params = inboxParams(inbox);
    params.put("readAt", Timestamp.from(at));
    params.put("id", id);
    jdbc.sql(
            "INSERT INTO notification_reads (notification_id, user_id, read_at)"
                + " SELECT n.id, :userId, :readAt FROM notifications n"
                + inboxFilter(inbox)
                + " AND n.id = :id ON CONFLICT (notification_id, user_id) DO NOTHING")
        .params(params)
        .update();
    return jdbc.sql("SELECT count(*) FROM notifications n" + inboxFilter(inbox) + " AND n.id = :id")
            .params(params)
            .query(Integer.class)
            .single()
        > 0;
  }

  @Override
  public void markAllRead(Inbox inbox, Instant at) {
    Map<String, @Nullable Object> params = inboxParams(inbox);
    params.put("readAt", Timestamp.from(at));
    jdbc.sql(
            "INSERT INTO notification_reads (notification_id, user_id, read_at)"
                + " SELECT n.id, :userId, :readAt FROM notifications n"
                + inboxFilter(inbox)
                + " ON CONFLICT (notification_id, user_id) DO NOTHING")
        .params(params)
        .update();
  }

  @Override
  public int deleteCreatedBefore(Instant cutoff, int max) {
    return jdbc.sql(
            "DELETE FROM notifications WHERE id IN (SELECT id FROM notifications"
                + " WHERE created_at < :cutoff ORDER BY created_at LIMIT :max)")
        .param("cutoff", Timestamp.from(cutoff))
        .param("max", max)
        .update();
  }

  /** WHERE clause of the inbox over alias n, with the parameters of {@link #inboxParams}. */
  private static String inboxFilter(Inbox inbox) {
    return inbox.audience() == Audience.GUEST
        ? " WHERE n.audience = :audience AND n.guest_id = :guestId"
        : " WHERE n.audience = :audience";
  }

  /** The reader and the inbox, as named parameters; the callers add their own to the map. */
  private static Map<String, @Nullable Object> inboxParams(Inbox inbox) {
    Map<String, @Nullable Object> params = new HashMap<>();
    params.put("userId", inbox.userId());
    params.put("audience", inbox.audience().name());
    if (inbox.audience() == Audience.GUEST) {
      params.put("guestId", Objects.requireNonNull(inbox.guestId()));
    }
    return params;
  }

  private record Key(Instant createdAt, Long id) {}

  /** The sort key of the item the cursor stands after, read again from its row in this inbox. */
  private Key keyOf(Inbox inbox, String cursor) {
    Map<String, @Nullable Object> params = inboxParams(inbox);
    params.put("id", MaskedCursor.idOf(masker, IdKind.NOTIFICATION, cursor));
    return jdbc.sql(
            "SELECT n.created_at, n.id FROM notifications n"
                + inboxFilter(inbox)
                + " AND n.id = :id")
        .params(params)
        .query((row, n) -> new Key(row.getTimestamp("created_at").toInstant(), row.getLong("id")))
        .optional()
        .orElseThrow(InvalidCursorException::new);
  }

  private Notification notification(ResultSet row, int n) throws SQLException {
    Timestamp readAt = row.getTimestamp("read_at");
    long guestId = row.getLong("guest_id");
    @Nullable Long guest = row.wasNull() ? null : guestId;
    return new Notification(
        row.getLong("id"),
        row.getObject("event_id", UUID.class),
        NotificationType.valueOf(row.getString("type")),
        guest,
        TargetKind.valueOf(row.getString("target_kind")),
        row.getLong("target_id"),
        json.readValue(row.getString("facts"), FACTS),
        row.getTimestamp("created_at").toInstant(),
        readAt == null ? null : readAt.toInstant());
  }
}
