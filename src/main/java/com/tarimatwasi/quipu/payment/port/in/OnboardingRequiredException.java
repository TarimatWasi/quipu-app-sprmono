package com.tarimatwasi.quipu.payment.port.in;

/** The guest of the session has not finished the onboarding (RN-33). */
public class OnboardingRequiredException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public OnboardingRequiredException() {
    super("The guest has not finished the onboarding (RN-33).");
  }
}
