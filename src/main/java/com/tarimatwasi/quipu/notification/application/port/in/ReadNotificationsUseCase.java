package com.tarimatwasi.quipu.notification.application.port.in;

import com.tarimatwasi.quipu.notification.application.port.in.RaiseNotificationUseCase.Kind;
import com.tarimatwasi.quipu.notification.application.port.in.RaiseNotificationUseCase.Target;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** The bell of the account of the session: its notifications and its own read state. */
public interface ReadNotificationsUseCase {

  /**
   * Who reads: the account and, for a guest, its guest. Always the one of the session (RN-09).
   *
   * @param userId the account
   * @param guestId the guest of a GUEST account; null for an administrator
   */
  record Reader(Long userId, @Nullable Long guestId) {

    /** An administrator reads the shared inbox of the administrators. */
    public boolean administrator() {
      return guestId == null;
    }
  }

  /**
   * One notification as its reader sees it.
   *
   * @param guestId the guest it is about (or for); null when none
   */
  record NotificationView(
      Long id,
      Kind kind,
      Target target,
      Long targetId,
      @Nullable Long guestId,
      Map<String, String> facts,
      Instant createdAt,
      @Nullable Instant readAt) {}

  /** One page, newest first, and how many the reader has not read (the badge). */
  record NotificationPage(CursorPage<NotificationView> page, long unreadCount) {}

  /** The notifications of the reader, newest first; only the unread ones if asked. */
  NotificationPage list(Reader reader, boolean unreadOnly, PageQuery page);

  /**
   * Marks one as read; an already read one keeps its first read instant.
   *
   * @throws NotificationNotFoundException if the reader has no notification with that id
   */
  void read(Reader reader, Long id);

  /** Marks every notification of the reader as read. */
  void readAll(Reader reader);
}
