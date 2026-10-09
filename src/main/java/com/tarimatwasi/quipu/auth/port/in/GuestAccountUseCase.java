package com.tarimatwasi.quipu.auth.port.in;

import java.util.Collection;
import java.util.Map;

/** What the other modules may ask the access accounts about the guests they own (RF-11). */
public interface GuestAccountUseCase {

  /**
   * The state of one guest's account.
   *
   * @param canLogIn the account exists and is not disabled
   * @param passwordChosen the guest chose its own password: no temporary one is pending (RF-12)
   */
  record GuestAccountState(boolean canLogIn, boolean passwordChosen) {}

  /** The state of the account of each guest; a guest without an account is not in the map. */
  Map<Long, GuestAccountState> stateOf(Collection<Long> guestIds);
}
