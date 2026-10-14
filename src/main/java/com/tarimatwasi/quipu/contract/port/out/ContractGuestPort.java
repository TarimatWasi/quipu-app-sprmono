package com.tarimatwasi.quipu.contract.port.out;

public interface ContractGuestPort {

  /** What a contract needs to know about its guest. */
  record GuestProfile(boolean active, boolean hasContractType) {}

  /**
   * What the contract needs to know about the guest.
   *
   * @throws com.tarimatwasi.quipu.guest.port.in.GuestNotFoundException if none exists
   */
  GuestProfile profileOf(Long guestId);
}
