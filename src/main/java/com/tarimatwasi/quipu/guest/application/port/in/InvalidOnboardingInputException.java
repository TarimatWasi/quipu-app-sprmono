package com.tarimatwasi.quipu.guest.application.port.in;

/** An input of the onboarding that the schema let through but the rules do not take. */
public class InvalidOnboardingInputException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** What is wrong. */
  public enum Reason {
    /** Not a JPG, PNG or PDF by content, empty or over 5 MB (RF-15). */
    DOCUMENT_INVALID_FILE,
    /** The consent was not accepted (RN-14). */
    CONSENT_NOT_ACCEPTED,
    /** The consent accepted is not the version in force. */
    CONSENT_VERSION
  }

  private final Reason reason;

  /** Creates the exception for the reason. */
  public InvalidOnboardingInputException(Reason reason) {
    super("Invalid onboarding input: " + reason);
    this.reason = reason;
  }

  /** What is wrong. */
  public Reason reason() {
    return reason;
  }
}
