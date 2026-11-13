package com.tarimatwasi.quipu.shared.occupancy;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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

  /**
   * TAR-237: the contract that makes {@link #environmentTaken} true, the one starting first; read
   * only to name it in the 409.
   */
  Optional<Occupancy> contractCrossing(
      Long environmentId, LocalDate start, @Nullable LocalDate end);

  /** TAR-237 (A2): every contract of those environments, as the range it occupies. */
  List<Occupancy> occupanciesOf(Collection<Long> environmentIds);

  /**
   * All historical owners, including contracts cancelled before their start, for visible labels.
   */
  Set<Long> guestsOfEnvironment(Long environmentId);

  /** The id of a contract of the guest in force today, if it has one (RN-12). */
  Optional<Long> guestContractInForce(Long guestId);

  /** TAR-237 (A3): every contract of those guests, as the range it occupies. */
  List<Occupancy> occupanciesOfGuests(Collection<Long> guestIds);

  /**
   * TAR-237 (A4.1): which of those guests have a contract in force today, as {@link
   * #guestContractInForce} says.
   */
  Set<Long> guestsWithContractInForce(Collection<Long> guestIds);
}
