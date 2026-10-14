package com.tarimatwasi.quipu.contract.port.out;

public interface ContractEnvironmentPort {

  /**
   * Locks the environment until the transaction ends, so who holds it is decided one request at a
   * time. Needs a transaction.
   *
   * @throws com.tarimatwasi.quipu.environment.port.in.EnvironmentNotFoundException if none exists
   */
  void lock(Long environmentId);
}
