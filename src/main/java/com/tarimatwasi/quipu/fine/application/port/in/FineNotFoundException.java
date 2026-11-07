package com.tarimatwasi.quipu.fine.application.port.in;

import java.io.Serial;

/** No fine has the requested id. */
public class FineNotFoundException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public FineNotFoundException() {
    super("Fine not found");
  }
}
