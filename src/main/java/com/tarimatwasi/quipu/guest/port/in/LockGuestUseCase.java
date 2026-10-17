package com.tarimatwasi.quipu.guest.port.in;

/** What the contracts need to read the debt of a guest one request at a time (TAR-192). */
public interface LockGuestUseCase {

  /**
   * Locks the guest until the transaction of the caller ends, so two simultaneous contracts of the
   * guest read its debt one after the other. Lock order: environment, guest, contract, payment.
   * Needs a transaction.
   *
   * @throws GuestNotFoundException if there is no guest with that id
   */
  void lock(Long id);
}
