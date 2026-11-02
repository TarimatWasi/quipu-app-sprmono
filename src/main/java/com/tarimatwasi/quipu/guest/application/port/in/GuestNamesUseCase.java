package com.tarimatwasi.quipu.guest.application.port.in;

import java.util.Collection;
import java.util.Map;

/** TAR-237 (A2): the names of the guests that occupy the environments, for the administrator. */
public interface GuestNamesUseCase {

  /**
   * The full name of each guest that has one; a guest without name yet (it has not completed the
   * onboarding) or an unknown id adds nothing.
   */
  Map<Long, String> namesOf(Collection<Long> guestIds);
}
