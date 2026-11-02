package com.tarimatwasi.quipu.shared.occupancy;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * What the modules that own an environment or a guest ask the contracts. It lives in {@code shared}
 * so that those modules do not depend on the contract module, which already depends on them
 * (modules may not form a cycle).
 */
public interface ContractOccupancyQuery {

  /** Whether a contract in force holds the environment today (RN-01, RN-12, RN-20). */
  boolean environmentOccupied(Long environmentId);

  /**
   * TAR-237 (RN-20): whether a contract not terminated holds the environment on at least one day of
   * the range, so a stay cannot take it.
   *
   * @param end the last day of the range; null for an open-ended range
   */
  boolean environmentTaken(Long environmentId, LocalDate start, @Nullable LocalDate end);

  /** TAR-237 (A2): every contract of those environments, as the range it occupies. */
  List<Occupancy> occupanciesOf(Collection<Long> environmentIds);

  /** The id of a contract of the guest in force today, if it has one (RN-12). */
  Optional<Long> guestContractInForce(Long guestId);
}
