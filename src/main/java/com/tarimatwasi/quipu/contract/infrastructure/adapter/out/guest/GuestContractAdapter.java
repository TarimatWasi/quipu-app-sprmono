package com.tarimatwasi.quipu.contract.infrastructure.adapter.out.guest;

import com.tarimatwasi.quipu.contract.application.port.out.ContractGuestPort;
import com.tarimatwasi.quipu.guest.application.port.in.LockGuestUseCase;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.GuestKind;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.GuestState;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.GuestSummaryView;
import org.springframework.stereotype.Component;

/** The contract module asks the guest module, through its exposed port, about one guest. */
@Component
public class GuestContractAdapter implements ContractGuestPort {

  private final ManageGuestsUseCase guests;
  private final LockGuestUseCase locks;

  GuestContractAdapter(ManageGuestsUseCase guests, LockGuestUseCase locks) {
    this.guests = guests;
    this.locks = locks;
  }

  @Override
  public void lock(Long guestId) {
    locks.lock(guestId);
  }

  @Override
  public GuestProfile profileOf(Long guestId) {
    GuestSummaryView guest = guests.get(guestId).summary();
    return new GuestProfile(
        guest.status() == GuestState.ACTIVE, guest.type() == GuestKind.CONTRACT);
  }
}
