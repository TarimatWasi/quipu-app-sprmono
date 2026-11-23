package com.tarimatwasi.quipu.shared.events;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Captured when the event is published: asynchronous listeners have neither the security context
 * nor the request. {@code eventId} is the CloudEvents {@code id} and the idempotency key of every
 * consumer, since the outbox delivers at least once.
 *
 * @param eventId unique per occurrence
 * @param occurredAt the instant of the clock of the application
 * @param actorUserId the authenticated account, or null for the system (startup, scheduled work)
 */
public record EventMetadata(UUID eventId, Instant occurredAt, @Nullable Long actorUserId) {}
