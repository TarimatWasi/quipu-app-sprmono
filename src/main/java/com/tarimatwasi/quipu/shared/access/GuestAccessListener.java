package com.tarimatwasi.quipu.shared.access;

/**
 * What the auth module asks of the guest module around the access of a guest. Implemented by the
 * guest module; both methods join the transaction of the caller.
 *
 * <p>Lock order of every change to a guest's access (deadlock-free): the guest row first, then its
 * account, then its accesses.
 */
public interface GuestAccessListener {

  /** Locks the row of the guest until the caller's commit; nothing if there is no such guest. */
  void lockGuest(Long guestId);

  /**
   * The guest chose its password with an access: a guest pending activation starts its onboarding
   * (D-30, RN-36); any other state is kept.
   */
  void accessUsed(Long guestId);
}
