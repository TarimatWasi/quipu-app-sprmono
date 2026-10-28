package com.tarimatwasi.quipu.shared.masking;

/**
 * An id was masked or unmasked with no session (TAR-227). It fails closed: without a session there
 * is no key, and the configured key never masks by itself. A programming error, never the client's.
 */
public class NoMaskSessionException extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public NoMaskSessionException() {
    super("No session to mask ids with");
  }
}
