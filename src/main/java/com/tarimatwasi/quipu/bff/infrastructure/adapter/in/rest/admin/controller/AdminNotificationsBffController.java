package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.admin.controller;

import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.response.NotificationResponses;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.response.NotificationResponses.PagedNotificationsResponse;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.support.PageParams;
import com.tarimatwasi.quipu.guest.application.port.in.GuestNamesUseCase;
import com.tarimatwasi.quipu.notification.application.port.in.ReadNotificationsUseCase;
import com.tarimatwasi.quipu.notification.application.port.in.ReadNotificationsUseCase.NotificationPage;
import com.tarimatwasi.quipu.notification.application.port.in.ReadNotificationsUseCase.NotificationView;
import com.tarimatwasi.quipu.notification.application.port.in.ReadNotificationsUseCase.Reader;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The shared bell of the administrators; each one keeps its own read state (v0.27.0). */
@RestController
public class AdminNotificationsBffController {
  private static final String URL = "/bff/admin/notifications";

  private final ReadNotificationsUseCase notifications;
  private final GuestNamesUseCase names;
  private final IdMasker ids;

  public AdminNotificationsBffController(
      ReadNotificationsUseCase notifications, GuestNamesUseCase names, IdMasker ids) {
    this.notifications = notifications;
    this.names = names;
    this.ids = ids;
  }

  @GetMapping(URL)
  public PagedNotificationsResponse list(
      Authentication authentication,
      @RequestParam(name = "limit", defaultValue = "20") int limit,
      @RequestParam(name = "after", required = false) @Nullable String after,
      @RequestParam(name = "unread", defaultValue = "false") boolean unread) {
    NotificationPage page =
        notifications.list(reader(authentication), unread, PageParams.query(after, limit));
    return NotificationResponses.of(page, ids, namesOf(page));
  }

  /** The current names of the guests of the page, in one read; nothing is stored with them. */
  private Map<Long, String> namesOf(NotificationPage page) {
    Set<Long> guests =
        page.page().content().stream()
            .map(NotificationView::guestId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    return guests.isEmpty() ? Map.of() : names.namesOf(guests);
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

  private static Reader reader(Authentication authentication) {
    return new Reader(Long.valueOf(authentication.getName()), null);
  }
}
