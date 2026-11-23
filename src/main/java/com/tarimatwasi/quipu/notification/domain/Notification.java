package com.tarimatwasi.quipu.notification.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * One notification of the bell. It keeps facts only (its type, the resource and a few values such
 * as an amount or a month); the text is written when it is read, from the current data, so no
 * personal data is stored here.
 *
 * @param id internal id, masked by the BFF
 * @param eventId the domain event it comes from: one notification per event and audience
 * @param type what happened; it also fixes the audience
 * @param guestId the guest it is for (GUEST audience) or about (null when none)
 * @param targetKind the kind of resource to open
 * @param targetId the internal id of that resource
 * @param facts the values the text needs, by name; identifiers, amounts and dates as text
 * @param createdAt when the fact happened
 * @param readAt when the reader read it; null while unread
 */
public record Notification(
    @Nullable Long id,
    UUID eventId,
    NotificationType type,
    @Nullable Long guestId,
    TargetKind targetKind,
    Long targetId,
    Map<String, String> facts,
    Instant createdAt,
    @Nullable Instant readAt) {

  /** A fact value is short, has no letters except a month or date separator: never free text. */
  private static final Pattern FACT_VALUE = Pattern.compile("^[0-9.:\\-TZ]{1,32}$");

  public Notification {
    facts = Map.copyOf(facts);
    for (var fact : facts.entrySet()) {
      if (!FACT_VALUE.matcher(fact.getValue()).matches()) {
        throw new IllegalArgumentException("Not a fact value: " + fact.getKey());
      }
    }
    if (type.audience() == Audience.GUEST) {
      Objects.requireNonNull(guestId, "A guest notification names its guest");
    }
  }

  /** A notification about to be raised, not stored yet. */
  public static Notification raised(
      UUID eventId,
      NotificationType type,
      @Nullable Long guestId,
      TargetKind targetKind,
      Long targetId,
      Map<String, String> facts,
      Instant createdAt) {
    return new Notification(
        null, eventId, type, guestId, targetKind, targetId, facts, createdAt, null);
  }

  /** Who reads it. */
  public Audience audience() {
    return type.audience();
  }
}
