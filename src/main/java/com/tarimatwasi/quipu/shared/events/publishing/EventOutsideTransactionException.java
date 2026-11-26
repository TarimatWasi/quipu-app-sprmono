package com.tarimatwasi.quipu.shared.events.publishing;

import java.io.Serial;

/** A {@code @PublishEvent} method ran without a transaction: its event would be lost. */
public class EventOutsideTransactionException extends IllegalStateException {
  @Serial private static final long serialVersionUID = 1L;

  public EventOutsideTransactionException(String method) {
    super(method + " publishes events, so it must run inside a transaction (@Transactional)");
  }
}
