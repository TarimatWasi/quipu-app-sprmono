package com.tarimatwasi.quipu.notification.domain.event;

import com.tarimatwasi.quipu.shared.events.DomainEvent;
import com.tarimatwasi.quipu.shared.events.EventMetadata;
import org.jspecify.annotations.Nullable;

/**
 * A new notification reached a bell: the shared one of the administrators, or the one of its guest.
 * Open screens refresh their bell.
 */
public record NotificationRaised(
    EventMetadata metadata, Long notificationId, boolean forAdministrators, @Nullable Long guestId)
    implements DomainEvent {

  @Override
  public String subject() {
    return "notification/" + notificationId;
  }
}
