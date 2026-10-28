package com.tarimatwasi.quipu.guest.application.port.in;

/**
 * TAR-229: the guest has no access account yet, so it has no account email to set. Only a guest
 * registered before RF-11 that was never handed an access is in that case; generating an access
 * opens its account.
 */
public class GuestWithoutAccountException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public GuestWithoutAccountException() {
    super("The guest has no access account");
  }
}
