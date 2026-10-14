package com.tarimatwasi.quipu.contract.adapter.out.guest;

import com.tarimatwasi.quipu.contract.port.out.ContractGuestPort;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase.GuestKind;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase.GuestState;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase.GuestSummaryView;
import org.springframework.stereotype.Component;

/** The contract module asks the guest module, through its exposed port, about one guest. */
@Component
public class GuestContractAdapter implements ContractGuestPort {

  private final ManageGuestsUseCase guests;

  GuestContractAdapter(ManageGuestsUseCase guests) {
    this.guests = guests;
  }

  @Override
  public GuestProfile profileOf(Long guestId) {
    GuestSummaryView guest = guests.get(guestId).summary();
    return new GuestProfile(
        guest.status() == GuestState.ACTIVE, guest.type() == GuestKind.CONTRACT);
  }
}
