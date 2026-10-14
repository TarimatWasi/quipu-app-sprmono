package com.tarimatwasi.quipu.auth.port.in;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

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

  /** The guest that the account with that id belongs to; empty for an account of no guest. */
  Optional<Long> guestOf(Long accountId);

  /**
   * Whether an administrator account, active or not, owns that document: one person, one account
   * (D-34e), so a guest cannot be registered under it. An unknown document type is nobody's.
   */
  boolean documentBelongsToAdministrator(String documentType, String documentNumber);
}
