package com.tarimatwasi.quipu.environment.application.port.out;

import com.tarimatwasi.quipu.shared.occupancy.Occupancy;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Who occupies the environments: the contracts and the stays (RN-20, TAR-237). */
public interface EnvironmentOccupancyPort {

  /** Whether a contract in force, or a stay today or later, holds the environment (RN-12). */
  boolean isOccupied(Long environmentId);

  /**
   * TAR-237: the contract in force, else the first stay from today on, that makes {@link
   * #isOccupied} true; read only to name it in the 409.
   */
  Optional<Occupancy> occupantOf(Long environmentId);

  /** Every contract and stay of those environments, as the range each one occupies (A2). */
  List<Occupancy> occupanciesOf(Collection<Long> environmentIds);

  /** Historical label readers, even if a cancelled contract never occupied the environment. */
  Set<Long> labelReadersOf(Long environmentId);
}
