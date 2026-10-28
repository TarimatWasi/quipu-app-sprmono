package com.tarimatwasi.quipu.contract.application.port.out;

public interface ContractGuestPort {

  /** What a contract needs to know about its guest. */
  record GuestProfile(boolean active, boolean hasContractType) {}

  /**
   * What the contract needs to know about the guest.
   *
   * @throws com.tarimatwasi.quipu.guest.application.port.in.GuestNotFoundException if none exists
   */
  GuestProfile profileOf(Long guestId);

  /**
   * Locks the guest until the transaction ends, so its debt is read one request at a time. Needs a
   * transaction; taken after the lock of the environment.
   *
   * @throws com.tarimatwasi.quipu.guest.application.port.in.GuestNotFoundException if none exists
   */
  void lock(Long guestId);
}
