package com.tarimatwasi.quipu.notification.application.service;

import com.tarimatwasi.quipu.notification.application.port.in.NotificationNotFoundException;
import com.tarimatwasi.quipu.notification.application.port.in.RaiseNotificationUseCase;
import com.tarimatwasi.quipu.notification.application.port.in.ReadNotificationsUseCase;
import com.tarimatwasi.quipu.notification.application.port.out.NotificationRepositoryPort;
import com.tarimatwasi.quipu.notification.application.port.out.NotificationRepositoryPort.Inbox;
import com.tarimatwasi.quipu.notification.domain.Audience;
import com.tarimatwasi.quipu.notification.domain.Notification;
import com.tarimatwasi.quipu.notification.domain.NotificationType;
import com.tarimatwasi.quipu.notification.domain.TargetKind;
import com.tarimatwasi.quipu.notification.domain.event.NotificationRaised;
import com.tarimatwasi.quipu.shared.events.PublishEvent;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The bell. Raising runs on the outbox delivery (no user); reading is always the account of the
 * session. Old notifications are deleted a few at a time while new ones arrive, so the retention
 * needs no scheduled task (BE-SPR-CON-01).
 */
@Service
public class NotificationService implements RaiseNotificationUseCase, ReadNotificationsUseCase {

  /** How long a notification stays in the bell (contract v0.27.0). */
  static final Duration RETENTION = Duration.ofDays(90);

  /** Expired notifications deleted per raised one: small, so the delivery stays short. */
  static final int PURGE_BATCH = 50;

  private final NotificationRepositoryPort notifications;
  private final Clock clock;

  NotificationService(NotificationRepositoryPort notifications, Clock clock) {
    this.notifications = notifications;
    this.clock = clock;
  }

  @Override
  @Transactional
  @PublishEvent(
      type = NotificationRaised.class,
      when = "#result.happened()",
      args = {"#result.notificationId()", "#result.forAdministrators()", "#result.guestId()"})
  public Raised raise(NewNotification raised) {
    NotificationType type = NotificationType.valueOf(raised.kind().name());
    var notification =
        Notification.raised(
            raised.eventId(),
            type,
            raised.guestId(),
            TargetKind.valueOf(raised.target().name()),
            raised.targetId(),
            raised.facts(),
            raised.occurredAt());
    notifications.deleteCreatedBefore(clock.instant().minus(RETENTION), PURGE_BATCH);
    boolean forAdministrators = type.audience() == Audience.ADMINISTRATORS;
    return notifications
        .insert(notification)
        .map(id -> new Raised(id, forAdministrators, raised.guestId(), true))
        .orElseGet(() -> new Raised(0L, forAdministrators, raised.guestId(), false));
  }

  @Override
  @Transactional(readOnly = true)
  @PreAuthorize("hasAnyRole('ADMIN', 'GUEST')")
  public NotificationPage list(Reader reader, boolean unreadOnly, PageQuery page) {
    Inbox inbox = inboxOf(reader);
    return new NotificationPage(
        notifications.find(inbox, unreadOnly, page).map(NotificationService::view),
        notifications.unreadCount(inbox));
  }

  @Override
  @Transactional
  @PreAuthorize("hasAnyRole('ADMIN', 'GUEST')")
  public void read(Reader reader, Long id) {
    if (!notifications.markRead(inboxOf(reader), id, clock.instant())) {
      throw new NotificationNotFoundException();
    }
  }

  @Override
  @Transactional
  @PreAuthorize("hasAnyRole('ADMIN', 'GUEST')")
  public void readAll(Reader reader) {
    notifications.markAllRead(inboxOf(reader), clock.instant());
  }

  private static Inbox inboxOf(Reader reader) {
    return reader.administrator()
        ? new Inbox(Audience.ADMINISTRATORS, null, reader.userId())
        : new Inbox(Audience.GUEST, reader.guestId(), reader.userId());
  }

  private static NotificationView view(Notification notification) {
    return new NotificationView(
        Objects.requireNonNull(notification.id()),
        Kind.valueOf(notification.type().name()),
        Target.valueOf(notification.targetKind().name()),
        notification.targetId(),
        notification.guestId(),
        notification.facts(),
        notification.createdAt(),
        notification.readAt());
  }
}
