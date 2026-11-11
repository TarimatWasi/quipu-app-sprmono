package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.support;

import java.io.Serial;

/** A stream cap was reached before the response was opened. */
public class ChangeStreamLimitException extends RuntimeException {
  @Serial private static final long serialVersionUID = 1L;

  public ChangeStreamLimitException() {
    super("Stream connection cap reached");
  }
}
