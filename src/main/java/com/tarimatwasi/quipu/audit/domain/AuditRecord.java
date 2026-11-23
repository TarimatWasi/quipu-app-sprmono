package com.tarimatwasi.quipu.audit.domain;

import com.tarimatwasi.quipu.shared.events.DomainEvent;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * One entry of the audit trail, shaped as a CloudEvents 1.0 envelope so the trail can be exported
 * to any CloudEvents consumer as it is: {@code id}, {@code source}, {@code type}, {@code subject},
 * {@code time}, plus two extensions: the actor and the guest of the fact. The data is the event
 * itself, without its metadata (already in the envelope).
 *
 * @param id CloudEvents id: the event id, unique per occurrence
 * @param source CloudEvents source: {@code /quipu/<module>}
 * @param type CloudEvents type: {@code com.tarimatwasi.quipu.<module>.<event-name>.v<version>}
 * @param subject CloudEvents subject: {@code <kind>/<internal id>}
 * @param time when the fact happened
 * @param actorUserId the account that caused it, or null for the system
 * @param guestId the guest it is about, if any (ARCO requests, Ley 29733)
 * @param event the fact, serialized by the adapter
 */
public record AuditRecord(
    UUID id,
    String source,
    String type,
    String subject,
    Instant time,
    @Nullable Long actorUserId,
    @Nullable Long guestId,
    DomainEvent event) {

  /** CloudEvents {@code specversion} of every entry. */
  public static final String SPEC_VERSION = "1.0";

  private static final String ROOT = "com.tarimatwasi.quipu.";
  private static final Pattern WORD_BOUNDARY = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])");

  /** The entry of a fact; its module is the first package below the root of the application. */
  public static AuditRecord of(DomainEvent event) {
    String module = moduleOf(event.getClass());
    String name =
        WORD_BOUNDARY
            .matcher(event.getClass().getSimpleName())
            .replaceAll("-")
            .toLowerCase(Locale.ROOT);
    return new AuditRecord(
        event.metadata().eventId(),
        "/quipu/" + module,
        ROOT + module + "." + name + ".v" + event.version(),
        event.subject(),
        event.metadata().occurredAt(),
        event.metadata().actorUserId(),
        event.guestId(),
        event);
  }

  private static String moduleOf(Class<?> type) {
    String name = type.getPackageName();
    if (!name.startsWith(ROOT)) {
      throw new IllegalArgumentException(type.getName() + " is not an event of Quipu");
    }
    int end = name.indexOf('.', ROOT.length());
    return end < 0 ? name.substring(ROOT.length()) : name.substring(ROOT.length(), end);
  }
}
