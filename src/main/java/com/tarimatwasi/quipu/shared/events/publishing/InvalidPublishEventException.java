package com.tarimatwasi.quipu.shared.events.publishing;

import java.io.Serial;
import java.util.List;

/** A {@code @PublishEvent} that cannot build its event: the application does not start. */
public class InvalidPublishEventException extends IllegalStateException {
  @Serial private static final long serialVersionUID = 1L;

  public InvalidPublishEventException(List<String> errors) {
    super("Invalid @PublishEvent: " + String.join("; ", errors));
  }
}
