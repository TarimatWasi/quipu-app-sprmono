package com.tarimatwasi.quipu.guest.port.out;

import java.util.Collection;
import java.util.Map;

/** What the guest module needs to know about the access account that belongs to a guest. */
public interface GuestAccountPort {

  /**
   * What the account of each guest says. A guest without an account is not in the map, which is the
   * same as an account that cannot log in and has not chosen a password.
   */
  Map<Long, AccountState> stateOf(Collection<Long> guestIds);

  /**
   * The state of one guest's account.
   *
   * @param canLogIn the account exists, has a password and is not disabled
   * @param passwordChosen the guest chose its own password (no temporary one is pending)
   */
  record AccountState(boolean canLogIn, boolean passwordChosen) {}
}
