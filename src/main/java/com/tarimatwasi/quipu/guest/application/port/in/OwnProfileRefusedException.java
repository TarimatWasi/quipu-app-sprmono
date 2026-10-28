package com.tarimatwasi.quipu.guest.application.port.in;

/**
 * The guest of the session may not edit its profile on that route (RN-09, RN-33): the route is of
 * the other type of guest, or the onboarding is not complete yet.
 */
public class OwnProfileRefusedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Why the edit is refused. */
  public enum Reason {
    /** The route belongs to the other type of guest. */
    OTHER_GUEST_TYPE,
    /** The guest has not completed the onboarding. */
    ONBOARDING_REQUIRED
  }

  private final Reason reason;

  /** Creates the exception for the reason. */
  public OwnProfileRefusedException(Reason reason) {
    super("The profile cannot be edited: " + reason);
    this.reason = reason;
  }

  /** Why the edit is refused. */
  public Reason reason() {
    return reason;
  }
}
