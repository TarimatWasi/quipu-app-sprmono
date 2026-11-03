package com.tarimatwasi.quipu.contract.application.port.out;

import java.util.Collection;
import java.util.Map;

public interface ContractEnvironmentPort {

  /**
   * Locks the environment until the transaction ends, so who holds it is decided one request at a
   * time. Needs a transaction.
   *
   * @return whether the environment is active, read under the lock
   * @throws com.tarimatwasi.quipu.environment.application.port.in.EnvironmentNotFoundException if
   *     none exists
   */
  boolean lock(Long environmentId);

  /** How an environment is named on a contract («Habitación 201», TAR-237). */
  record EnvironmentLabel(String code, String type) {}

  /** The label of each environment; an id with no environment adds nothing. */
  Map<Long, EnvironmentLabel> labelsOf(Collection<Long> environmentIds);
}
