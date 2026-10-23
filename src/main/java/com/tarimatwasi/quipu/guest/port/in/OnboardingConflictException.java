package com.tarimatwasi.quipu.guest.port.in;

/**
 * The onboarding is not where the operation needs it (RN-33), or the documents are full (RN-37).
 */
public class OnboardingConflictException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Why the operation cannot run now. */
  public enum Reason {
    /** A step before its predecessor. */
    STEP_OUT_OF_ORDER,
    /** The guest is already active. */
    ALREADY_COMPLETED,
    /** The guest already has the 5 documents it may keep. */
    DOCUMENT_LIMIT_REACHED
  }

  private final Reason reason;

  /** Creates the exception for the reason. */
  public OnboardingConflictException(Reason reason) {
    super("Onboarding conflict: " + reason);
    this.reason = reason;
  }

  /** Why the operation cannot run now. */
  public Reason reason() {
    return reason;
  }
}
