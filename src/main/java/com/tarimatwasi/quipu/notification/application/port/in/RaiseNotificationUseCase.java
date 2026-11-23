package com.tarimatwasi.quipu.notification.application.port.in;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Raises the notification of a confirmed fact. Called by the outbox delivery, which may repeat an
 * event: raising the same event twice keeps a single notification.
 */
public interface RaiseNotificationUseCase {

  /** What the bell shows, by type (contract: NotificationType). */
  enum Kind {
    PAYMENT_PROOF_SUBMITTED,
    ONBOARDING_COMPLETED,
    GUEST_DOCUMENT_UPLOADED,
    CONTRACT_EXPIRING,
    PAYMENT_OVERDUE,
    STAY_ENDING,
    ACCESS_EXPIRED,
    PAYMENT_APPROVED,
    PAYMENT_REJECTED,
    REFUND_CREATED,
    FINE_APPLIED,
    CONTRACT_SIGNED,
    CONTRACT_TERMINATED,
    PAYMENT_DUE
  }

  /** The resource the notification opens (contract: NotificationTarget.kind). */
  enum Target {
    PAYMENT,
    GUEST,
    CONTRACT,
    FINE,
    REFUND,
    DOCUMENT
  }

  /**
   * A notification to raise.
   *
   * @param eventId the domain event it comes from (idempotency key)
   * @param kind what happened; it fixes who reads it
   * @param guestId the guest it is for or about; required for the kinds a guest reads
   * @param target the kind of resource to open
   * @param targetId its internal id
   * @param facts identifiers, amounts and dates the text needs, by name; never free text
   * @param occurredAt when the fact happened
   */
  record NewNotification(
      UUID eventId,
      Kind kind,
      @Nullable Long guestId,
      Target target,
      Long targetId,
      Map<String, String> facts,
      Instant occurredAt) {}

  /** The outcome: {@code happened} is false when the event had already raised its notification. */
  record Raised(
      Long notificationId, boolean forAdministrators, @Nullable Long guestId, boolean happened) {}

  /** Raises it once per event. */
  Raised raise(NewNotification notification);
}
