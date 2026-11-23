package com.tarimatwasi.quipu.notification.application.port.out;

import com.tarimatwasi.quipu.notification.domain.Audience;
import com.tarimatwasi.quipu.notification.domain.Notification;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** The notifications and the read state of each reader. */
public interface NotificationRepositoryPort {

  /** Which bell, and whose read state: the audience, its guest (GUEST) and the account. */
  record Inbox(Audience audience, @Nullable Long guestId, Long userId) {}

  /** Stores it; empty if its event already raised a notification of that type. */
  Optional<Long> insert(Notification notification);

  /** The notifications of the inbox, newest first, with the read instant of its reader. */
  CursorPage<Notification> find(Inbox inbox, boolean unreadOnly, PageQuery page);

  /** How many notifications of the inbox its reader has not read. */
  long unreadCount(Inbox inbox);

  /** Marks one as read; false if the inbox has no notification with that id. */
  boolean markRead(Inbox inbox, Long id, Instant at);

  /** Marks every notification of the inbox as read. */
  void markAllRead(Inbox inbox, Instant at);

  /** Deletes at most {@code max} notifications created before the cutoff (retention). */
  int deleteCreatedBefore(Instant cutoff, int max);
}
