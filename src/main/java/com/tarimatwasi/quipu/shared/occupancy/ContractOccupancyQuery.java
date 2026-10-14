package com.tarimatwasi.quipu.shared.occupancy;

/**
 * What the modules that own an environment or a guest ask the contracts. It lives in {@code shared}
 * so that those modules do not depend on the contract module, which already depends on them
 * (modules may not form a cycle).
 */
public interface ContractOccupancyQuery {

  /** Whether a contract in force holds the environment today (RN-01, RN-12, RN-20). */
  boolean environmentOccupied(Long environmentId);
}
