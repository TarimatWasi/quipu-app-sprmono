package com.tarimatwasi.quipu.contract.application.service;

import com.tarimatwasi.quipu.contract.domain.Contract;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Which contract of a guest is "its contract": the one H2 shows to the guest (TAR-237) and A3.2
 * shows to the administrator (TAR-359). One rule for both, so the two screens never disagree.
 */
final class CurrentContract {

  /** By start date, then by id, so two contracts that start the same day keep one order. */
  static final Comparator<Contract> BY_START =
      Comparator.comparing(Contract::startDate).thenComparing(Contract::id);

  private CurrentContract() {}

  /**
   * RN-07: the one in force today; else the one in force that starts first (it is in force since it
   * was loaded); else the latest one, if all ended. Empty only if the guest never had a contract.
   */
  static Optional<Contract> of(List<Contract> all, LocalDate today) {
    Optional<Contract> running =
        all.stream().filter(c -> c.occupies(today) && !c.startDate().isAfter(today)).min(BY_START);
    if (running.isPresent()) {
      return running;
    }
    Optional<Contract> coming = all.stream().filter(c -> c.occupies(today)).min(BY_START);
    return coming.isPresent() ? coming : all.stream().max(BY_START);
  }
}
