package com.tarimatwasi.quipu.environment.application.port.in;

/**
 * The environment is held by a contract or a stay, so it cannot be deactivated or take another one
 * in those dates (RN-12, RN-20, TAR-237).
 */
public class EnvironmentOccupiedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final boolean forStay;

  /** Creates the exception for a contract or a deactivation. */
  public EnvironmentOccupiedException() {
    this(false);
  }

  private EnvironmentOccupiedException(boolean forStay) {
    super("The environment is held by a contract or a stay");
    this.forStay = forStay;
  }

  /**
   * TAR-237: a stay asked for an environment taken in its dates, or an inactive one; the message
   * talks about the dates, not about finishing a contract.
   */
  public static EnvironmentOccupiedException forStay() {
    return new EnvironmentOccupiedException(true);
  }

  /** Whether it was raised by a stay (A3.5), not by a contract or a deactivation. */
  public boolean isForStay() {
    return forStay;
  }
}
