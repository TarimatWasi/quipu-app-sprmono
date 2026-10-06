package com.tarimatwasi.quipu.ambiente.port.in;

/** An edit that changes nothing is a malformed request. */
public class EmptyEnvironmentUpdateException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public EmptyEnvironmentUpdateException() {
    super("An update needs at least one field");
  }
}
