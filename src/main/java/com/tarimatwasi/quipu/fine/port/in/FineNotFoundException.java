package com.tarimatwasi.quipu.fine.port.in;

/** No fine has the requested id. */
public class FineNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public FineNotFoundException() {
    super("Fine not found");
  }
}
