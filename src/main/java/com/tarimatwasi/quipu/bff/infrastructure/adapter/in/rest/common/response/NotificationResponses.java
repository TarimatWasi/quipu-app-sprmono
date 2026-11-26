package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.support.NotificationTexts;
import com.tarimatwasi.quipu.notification.application.port.in.RaiseNotificationUseCase.Target;
import com.tarimatwasi.quipu.notification.application.port.in.ReadNotificationsUseCase.NotificationPage;
import com.tarimatwasi.quipu.notification.application.port.in.ReadNotificationsUseCase.NotificationView;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The bell as the contract shows it (v0.27.0: Notification, PagedNotifications; v0.30.0: guestId of
 * DOCUMENT).
 */
public final class NotificationResponses {

  private NotificationResponses() {}

  /**
   * The resource a notification opens, with its ids masked for the session. A document lives under
   * its guest, so DOCUMENT also names the guest (v0.30.0); the other kinds leave it out.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record TargetResponse(String kind, UUID id, @Nullable UUID guestId) {}

  /** One notification, its text written now in Spanish. */
  public record NotificationResponse(
      UUID id,
      String type,
      String title,
      String body,
      Instant createdAt,
      @Nullable Instant readAt,
      @Nullable TargetResponse target) {}

  /** One page and the unread count of the badge. */
  public record PagedNotificationsResponse(
      List<NotificationResponse> content, @Nullable String nextCursor, long unreadCount) {}

  /** The page of the use case as the contract shows it. */
  public static PagedNotificationsResponse of(NotificationPage page, IdMasker ids) {
    return of(page, ids, Map.of());
  }

  /** The page with the current names of its guests, by id; a guest without one is "un huésped". */
  public static PagedNotificationsResponse of(
      NotificationPage page, IdMasker ids, Map<Long, String> guestNames) {
    PagedResponse<NotificationResponse> mapped =
        PagedResponse.of(page.page(), view -> notification(view, ids, guestNames));
    return new PagedNotificationsResponse(
        mapped.content(), mapped.nextCursor(), page.unreadCount());
  }

  private static NotificationResponse notification(
      NotificationView view, IdMasker ids, Map<Long, String> guestNames) {
    String guestName = view.guestId() == null ? null : guestNames.get(view.guestId());
    NotificationTexts.Text text = NotificationTexts.of(view.kind(), view.facts(), guestName);
    return new NotificationResponse(
        ids.mask(IdKind.NOTIFICATION, view.id()),
        view.kind().name(),
        text.title(),
        text.body(),
        view.createdAt(),
        view.readAt(),
        target(view, ids));
  }

  private static TargetResponse target(NotificationView view, IdMasker ids) {
    UUID guest =
        view.target() == Target.DOCUMENT && view.guestId() != null
            ? ids.mask(IdKind.GUEST, view.guestId())
            : null;
    return new TargetResponse(
        view.target().name(), ids.mask(kindOf(view.target()), view.targetId()), guest);
  }

  private static IdKind kindOf(Target target) {
    return switch (target) {
      case PAYMENT -> IdKind.PAYMENT;
      case GUEST -> IdKind.GUEST;
      case CONTRACT -> IdKind.CONTRACT;
      case FINE -> IdKind.FINE;
      case REFUND -> IdKind.REFUND;
      case DOCUMENT -> IdKind.GUEST_DOCUMENT;
    };
  }
}
