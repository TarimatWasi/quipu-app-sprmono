package com.tarimatwasi.quipu.environment.port.in;

/** What the contracts need to decide who holds an environment, one request at a time (RN-20). */
public interface LockEnvironmentUseCase {

  /**
   * Locks the environment until the transaction of the caller ends, so two simultaneous contracts
   * on it, or a contract and a deactivation, are decided one after the other. Needs a transaction.
   *
   * @throws EnvironmentNotFoundException if there is no environment with that id
   */
  void lock(Long id);
}
