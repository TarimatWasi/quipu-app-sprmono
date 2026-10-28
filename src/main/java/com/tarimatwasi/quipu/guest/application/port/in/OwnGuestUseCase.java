package com.tarimatwasi.quipu.guest.application.port.in;

import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.GuestKind;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.GuestState;
import com.tarimatwasi.quipu.guest.application.port.in.ManageStaysUseCase.StayView;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * What the other modules may read of the guest of a session (RN-09): its type, its state and its
 * stays. The caller passes the guest of the session, never one named in the request.
 */
public interface OwnGuestUseCase {

  /** The type and the state of the guest. */
  record OwnGuestView(Long id, GuestKind type, GuestState status) {}

  /**
   * The guest.
   *
   * @throws GuestNotFoundException if there is none
   */
  OwnGuestView own(Long guestId);

  /** Every stay of the guest, in no particular order. */
  List<StayView> stays(Long guestId);

  /** The stay a summary shows: the one in course that day, else the next one, else the last one. */
  Optional<StayView> relevantStay(Long guestId, LocalDate today);
}
