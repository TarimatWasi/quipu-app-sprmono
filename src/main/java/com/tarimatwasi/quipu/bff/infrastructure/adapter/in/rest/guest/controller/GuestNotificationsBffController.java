package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.guest.controller;

import com.tarimatwasi.quipu.auth.application.port.in.GuestAccountUseCase;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.response.NotificationResponses;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.response.NotificationResponses.PagedNotificationsResponse;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.support.PageParams;
import com.tarimatwasi.quipu.notification.application.port.in.NotificationNotFoundException;
import com.tarimatwasi.quipu.notification.application.port.in.ReadNotificationsUseCase;
import com.tarimatwasi.quipu.notification.application.port.in.ReadNotificationsUseCase.Reader;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The bell of the guest of the session, never one named in the request (RN-09, v0.27.0). */
@RestController
public class GuestNotificationsBffController {
  private static final String URL = "/bff/guest/notifications";

  private final ReadNotificationsUseCase notifications;
  private final GuestAccountUseCase accounts;
  private final IdMasker ids;

  public GuestNotificationsBffController(
      ReadNotificationsUseCase notifications, GuestAccountUseCase accounts, IdMasker ids) {
    this.notifications = notifications;
    this.accounts = accounts;
    this.ids = ids;
  }

  @GetMapping(URL)
  public PagedNotificationsResponse list(
      Authentication authentication,
      @RequestParam(name = "limit", defaultValue = "20") int limit,
      @RequestParam(name = "after", required = false) @Nullable String after,
      @RequestParam(name = "unread", defaultValue = "false") boolean unread) {
    return NotificationResponses.of(
        notifications.list(reader(authentication), unread, PageParams.query(after, limit)), ids);
  }

  @PostMapping(URL + "/{id}/read")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void read(Authentication authentication, @PathVariable UUID id) {
    notifications.read(reader(authentication), ids.unmask(IdKind.NOTIFICATION, id));
  }

  @PostMapping(URL + "/read-all")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void readAll(Authentication authentication) {
    notifications.readAll(reader(authentication));
  }

  private Reader reader(Authentication authentication) {
    Long userId = Long.valueOf(authentication.getName());
    // GuestAreaFilter already refused an account without a guest; this keeps the 404 if not.
    Long guestId = accounts.guestOf(userId).orElseThrow(NotificationNotFoundException::new);
    return new Reader(userId, guestId);
  }
}
