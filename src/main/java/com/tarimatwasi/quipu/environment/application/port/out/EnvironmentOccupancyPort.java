package com.tarimatwasi.quipu.environment.application.port.out;

import com.tarimatwasi.quipu.shared.occupancy.Occupancy;
import java.util.Collection;
import java.util.List;

/** Who occupies the environments: the contracts and the stays (RN-20, TAR-237). */
public interface EnvironmentOccupancyPort {

  /** Whether a contract in force, or a stay today or later, holds the environment (RN-12). */
  boolean isOccupied(Long environmentId);

  /** Every contract and stay of those environments, as the range each one occupies (A2). */
  List<Occupancy> occupanciesOf(Collection<Long> environmentIds);
}
