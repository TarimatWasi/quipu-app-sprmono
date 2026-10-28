package com.tarimatwasi.quipu.fine.infrastructure.adapter.out.guest;

import com.tarimatwasi.quipu.fine.application.port.out.FineGuestPort;
import com.tarimatwasi.quipu.guest.application.port.in.GuestNotFoundException;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase;
import org.springframework.stereotype.Component;

/** The fine module asks the guest module, through its exposed port, whether a guest exists. */
@Component
public class GuestFineAdapter implements FineGuestPort {

  private final ManageGuestsUseCase guests;

  GuestFineAdapter(ManageGuestsUseCase guests) {
    this.guests = guests;
  }

  @Override
  public boolean exists(Long guestId) {
    try {
      guests.get(guestId);
      return true;
    } catch (GuestNotFoundException missing) {
      return false;
    }
  }
}
