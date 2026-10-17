package com.tarimatwasi.quipu.shared.access;

/**
 * What the auth module tells the guest module when a guest uses an access: the activation link or a
 * valid temporary password. Implemented by the guest module; joins the transaction of the caller.
 */
public interface GuestAccessListener {

  /**
   * A guest pending activation starts its onboarding (D-30, RN-36); any other state is kept. The
   * row of the guest stays locked until the caller's commit.
   */
  void accessUsed(Long guestId);
}
