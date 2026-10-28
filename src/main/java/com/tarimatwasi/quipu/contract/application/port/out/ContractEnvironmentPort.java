package com.tarimatwasi.quipu.contract.application.port.out;

public interface ContractEnvironmentPort {

  /**
   * Locks the environment until the transaction ends, so who holds it is decided one request at a
   * time. Needs a transaction.
   *
   * @return whether the environment is active, read under the lock
   * @throws com.tarimatwasi.quipu.environment.port.in.EnvironmentNotFoundException if none exists
   */
  boolean lock(Long environmentId);
}
