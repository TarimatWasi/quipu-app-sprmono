package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.support;

/** A stream cap was reached before the response was opened. */
public class ChangeStreamLimitException extends RuntimeException {
  @java.io.Serial private static final long serialVersionUID = 1L;

  public ChangeStreamLimitException() {
    super("Stream connection cap reached");
  }
}
