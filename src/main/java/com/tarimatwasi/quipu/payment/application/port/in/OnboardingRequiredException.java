package com.tarimatwasi.quipu.payment.application.port.in;

import java.io.Serial;

/** The guest of the session has not finished the onboarding (RN-33). */
public class OnboardingRequiredException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public OnboardingRequiredException() {
    super("The guest has not finished the onboarding (RN-33).");
  }
}
