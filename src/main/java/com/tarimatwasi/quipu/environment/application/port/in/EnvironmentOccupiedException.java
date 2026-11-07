package com.tarimatwasi.quipu.environment.application.port.in;

import com.tarimatwasi.quipu.shared.occupancy.Occupancy;
import java.io.Serial;
import org.jspecify.annotations.Nullable;

/**
 * The environment is held by a contract or a stay, so it cannot be deactivated or take another one
 * in those dates (RN-12, RN-20, TAR-237).
 */
public class EnvironmentOccupiedException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final boolean forStay;

  // Never serialized: it only travels from the use case to the handler of the answer.
  private final transient @Nullable Occupancy occupant;

  /**
   * Creates the exception for a contract or a deactivation.
   *
   * @param occupant the contract or the stay that holds the environment, to name it in the answer
   */
  public EnvironmentOccupiedException(@Nullable Occupancy occupant) {
    this(false, occupant);
  }

  private EnvironmentOccupiedException(boolean forStay, @Nullable Occupancy occupant) {
    super("The environment is held by a contract or a stay");
    this.forStay = forStay;
    this.occupant = occupant;
  }

  /**
   * TAR-237: a stay asked for an environment taken in its dates, or an inactive one; the message
   * talks about the dates, not about finishing a contract.
   *
   * @param occupant who holds it; null for an inactive environment
   */
  public static EnvironmentOccupiedException forStay(@Nullable Occupancy occupant) {
    return new EnvironmentOccupiedException(true, occupant);
  }

  /** Whether it was raised by a stay (A3.5), not by a contract or a deactivation. */
  public boolean isForStay() {
    return forStay;
  }

  /** The contract or the stay that holds the environment, if it is known. */
  public @Nullable Occupancy occupant() {
    return occupant;
  }
}
