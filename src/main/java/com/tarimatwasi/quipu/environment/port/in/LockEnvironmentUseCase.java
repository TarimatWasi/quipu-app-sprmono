package com.tarimatwasi.quipu.environment.port.in;

/** What the contracts need to decide who holds an environment, one request at a time (RN-20). */
public interface LockEnvironmentUseCase {

  /**
   * Locks the environment until the transaction of the caller ends, so two simultaneous contracts
   * on it, or a contract and a deactivation, are decided one after the other. Needs a transaction.
   *
   * @return whether the locked environment is active; it cannot change until the lock is released
   * @throws EnvironmentNotFoundException if there is no environment with that id
   */
  boolean lock(Long id);
}
