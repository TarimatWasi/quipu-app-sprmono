package com.tarimatwasi.quipu.shared.paging;

/** A page cursor that this service did not issue, or that was altered. */
public class InvalidCursorException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception; the message never repeats the cursor that came in. */
  public InvalidCursorException() {
    super("Invalid page cursor");
  }
}
