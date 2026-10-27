package com.tarimatwasi.quipu.fine.adapter.out.guest;

import com.tarimatwasi.quipu.fine.port.out.FineGuestPort;
import com.tarimatwasi.quipu.guest.port.in.GuestNotFoundException;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase;
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
