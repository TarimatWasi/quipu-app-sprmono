package com.tarimatwasi.quipu.guest.application.port.out;

/** TAR-237 (RN-20): what the stays need from the environment they occupy. */
public interface StayEnvironmentPort {

  /**
   * Locks the environment until the transaction ends, so a stay and a contract (or a deactivation)
   * on it are decided one after the other. Needs a transaction.
   *
   * @return whether the locked environment is active
   * @throws com.tarimatwasi.quipu.environment.application.port.in.EnvironmentNotFoundException if
   *     there is no environment with that id
   */
  boolean lock(Long environmentId);
}
