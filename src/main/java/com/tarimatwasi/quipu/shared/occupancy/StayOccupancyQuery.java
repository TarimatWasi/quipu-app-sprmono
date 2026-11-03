package com.tarimatwasi.quipu.shared.occupancy;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * TAR-237 (RN-20): what the modules that own contracts or environments ask the stays of the
 * temporary guests, which occupy an environment during their dates like a contract. It lives in
 * {@code shared} so that those modules do not depend on the guest module (modules may not form a
 * cycle).
 */
public interface StayOccupancyQuery {

  /**
   * Whether a stay holds the environment on at least one day of the range.
   *
   * @param end the last day of the range; null for an open-ended range (a contract without end)
   */
  boolean environmentTaken(Long environmentId, LocalDate start, @Nullable LocalDate end);

  /**
   * TAR-237: the stay that makes {@link #environmentTaken} true, the one starting first; read only
   * to name it in the 409.
   */
  Optional<Occupancy> stayCrossing(Long environmentId, LocalDate start, @Nullable LocalDate end);

  /** Whether a stay holds the environment today or later, so it cannot be deactivated (RN-12). */
  boolean environmentOccupiedFrom(Long environmentId, LocalDate today);

  /** TAR-237 (A2): every stay of those environments, as the range it occupies. */
  List<Occupancy> occupanciesOf(Collection<Long> environmentIds);
}
