package com.tarimatwasi.quipu.guest.application.port.in;

/** The guest is inactive (RN-12, RN-18): reactivate it first (CU-28). */
public class GuestInactiveException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final Long guestId;

  /** Creates the exception for the inactive guest. */
  public GuestInactiveException(Long guestId) {
    super("The guest is inactive");
    this.guestId = guestId;
  }

  /** The id of the guest. */
  public Long guestId() {
    return guestId;
  }
}
