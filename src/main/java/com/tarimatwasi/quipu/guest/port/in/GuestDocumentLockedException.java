package com.tarimatwasi.quipu.guest.port.in;

/** The document can only be corrected while the guest is pending activation (RN-36). */
public class GuestDocumentLockedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public GuestDocumentLockedException() {
    super("The document of a guest that already activated its access cannot be corrected");
  }
}
