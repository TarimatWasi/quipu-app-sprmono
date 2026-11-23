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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
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

  private final JdbcTemplate jdbc;
  private final JsonMapper json;
  private final IdMasker masker;

  public NotificationJdbcAdapter(JdbcTemplate jdbc, JsonMapper json, IdMasker masker) {
    this.jdbc = jdbc;
    this.json = json;
    this.masker = masker;
  }

  @Override
  public Optional<Long> insert(Notification notification) {
    List<@Nullable Long> id =
        jdbc.queryForList(
            """
            INSERT INTO notifications (event_id, type, audience, guest_id, target_kind, target_id,
                                       facts, created_at)
            VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
            ON CONFLICT (event_id, type) DO NOTHING
            RETURNING id
            """,
            Long.class,
            notification.eventId(),
            notification.type().name(),
            notification.audience().name(),
            notification.guestId(),
            notification.targetKind().name(),
            notification.targetId(),
            json.writeValueAsString(notification.facts()),
            Timestamp.from(notification.createdAt()));
    return id.stream().filter(Objects::nonNull).findFirst();
  }

  @Override
  public CursorPage<Notification> find(Inbox inbox, boolean unreadOnly, PageQuery page) {
    List<Object> arguments = new ArrayList<>();
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT n.*, r.read_at FROM notifications n
            LEFT JOIN notification_reads r ON r.notification_id = n.id AND r.user_id = ?
            """);
    arguments.add(inbox.userId());
    sql.append(inboxFilter(inbox, arguments));
    if (unreadOnly) {
      sql.append(" AND r.read_at IS NULL");
    }
    if (page.after() != null) {
      Key after = keyOf(inbox, page.after());
      sql.append(" AND (n.created_at, n.id) < (?, ?)");
      arguments.add(Timestamp.from(after.createdAt()));
      arguments.add(after.id());
    }
    sql.append(" ORDER BY n.created_at DESC, n.id DESC LIMIT ?");
    // One more than the limit: the extra row only says that there is a next page.
    arguments.add(page.limit() + 1);
    List<Notification> rows = jdbc.query(sql.toString(), this::notification, arguments.toArray());
    return CursorPage.of(
        rows,
        page.limit(),
        row -> MaskedCursor.of(masker, IdKind.NOTIFICATION, Objects.requireNonNull(row.id())));
  }

  @Override
  public long unreadCount(Inbox inbox) {
    List<Object> arguments = new ArrayList<>();
    arguments.add(inbox.userId());
    Long count =
        jdbc.queryForObject(
            """
            SELECT count(*) FROM notifications n
            LEFT JOIN notification_reads r ON r.notification_id = n.id AND r.user_id = ?
            """
                + inboxFilter(inbox, arguments)
                + " AND r.read_at IS NULL",
            Long.class,
            arguments.toArray());
    return count == null ? 0 : count;
  }

  @Override
  public boolean markRead(Inbox inbox, Long id, Instant at) {
    List<Object> arguments = new ArrayList<>(List.of(inbox.userId(), Timestamp.from(at)));
    String filter = inboxFilter(inbox, arguments);
    arguments.add(id);
    jdbc.update(
        "INSERT INTO notification_reads (notification_id, user_id, read_at)"
            + " SELECT n.id, ?, ? FROM notifications n"
            + filter
            + " AND n.id = ? ON CONFLICT (notification_id, user_id) DO NOTHING",
        arguments.toArray());
    List<Object> exists = new ArrayList<>();
    String inInbox = inboxFilter(inbox, exists);
    exists.add(id);
    Integer found =
        jdbc.queryForObject(
            "SELECT count(*) FROM notifications n" + inInbox + " AND n.id = ?",
            Integer.class,
            exists.toArray());
    return found != null && found > 0;
  }

  @Override
  public void markAllRead(Inbox inbox, Instant at) {
    List<Object> arguments = new ArrayList<>(List.of(inbox.userId(), Timestamp.from(at)));
    jdbc.update(
        "INSERT INTO notification_reads (notification_id, user_id, read_at)"
            + " SELECT n.id, ?, ? FROM notifications n"
            + inboxFilter(inbox, arguments)
            + " ON CONFLICT (notification_id, user_id) DO NOTHING",
        arguments.toArray());
  }

  @Override
  public int deleteCreatedBefore(Instant cutoff, int max) {
    return jdbc.update(
        "DELETE FROM notifications WHERE id IN"
            + " (SELECT id FROM notifications WHERE created_at < ? ORDER BY created_at LIMIT ?)",
        Timestamp.from(cutoff),
        max);
  }

  /** WHERE clause of the inbox over alias n; appends its arguments. */
  private static String inboxFilter(Inbox inbox, List<Object> arguments) {
    arguments.add(inbox.audience().name());
    if (inbox.audience() == Audience.GUEST) {
      arguments.add(Objects.requireNonNull(inbox.guestId()));
      return " WHERE n.audience = ? AND n.guest_id = ?";
    }
    return " WHERE n.audience = ?";
  }

  private record Key(Instant createdAt, Long id) {}

  /** The sort key of the item the cursor stands after, read again from its row in this inbox. */
  private Key keyOf(Inbox inbox, String cursor) {
    long id = MaskedCursor.idOf(masker, IdKind.NOTIFICATION, cursor);
    List<Object> arguments = new ArrayList<>();
    String filter = inboxFilter(inbox, arguments);
    arguments.add(id);
    return jdbc
        .query(
            "SELECT n.created_at, n.id FROM notifications n" + filter + " AND n.id = ?",
            (row, n) -> new Key(row.getTimestamp("created_at").toInstant(), row.getLong("id")),
            arguments.toArray())
        .stream()
        .findFirst()
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
