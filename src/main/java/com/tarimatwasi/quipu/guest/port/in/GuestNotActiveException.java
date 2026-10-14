package com.tarimatwasi.quipu.guest.port.in;

/** The guest is not active, so it cannot take a stay (RN-35). */
public class GuestNotActiveException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final Long guestId;

  /** Creates the exception for the guest that is not active. */
  public GuestNotActiveException(Long guestId) {
    super("The guest is not active");
    this.guestId = guestId;
  }

  /** The id of the guest. */
  public Long guestId() {
    return guestId;
  }
}
