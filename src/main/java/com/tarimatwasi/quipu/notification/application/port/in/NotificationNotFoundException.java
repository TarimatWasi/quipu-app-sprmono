package com.tarimatwasi.quipu.notification.application.port.in;

import java.io.Serial;

/** The reader has no notification with that id (404, the same for one of another reader). */
public class NotificationNotFoundException extends RuntimeException {
  @Serial private static final long serialVersionUID = 1L;

  /** The same message for a missing one and for one of another reader. */
  public NotificationNotFoundException() {
    super("Notification not found");
  }
}
