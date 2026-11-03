package com.tarimatwasi.quipu.contract.infrastructure.adapter.out.guest;

import com.tarimatwasi.quipu.contract.application.port.out.ContractGuestPort;
import com.tarimatwasi.quipu.guest.application.port.in.GuestLabelsUseCase;
import com.tarimatwasi.quipu.guest.application.port.in.LockGuestUseCase;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.GuestKind;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.GuestState;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.GuestSummaryView;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** The contract module asks the guest module, through its exposed port, about one guest. */
@Component
public class GuestContractAdapter implements ContractGuestPort {

  private final ManageGuestsUseCase guests;
  private final LockGuestUseCase locks;
  private final GuestLabelsUseCase labels;

  GuestContractAdapter(
      ManageGuestsUseCase guests, LockGuestUseCase locks, GuestLabelsUseCase labels) {
    this.guests = guests;
    this.locks = locks;
    this.labels = labels;
  }

  @Override
  public Map<Long, GuestLabel> labelsOf(Collection<Long> guestIds) {
    Map<Long, GuestLabel> found = new HashMap<>();
    labels
        .labelsOf(guestIds)
        .forEach(
            (id, label) ->
                found.put(
                    id,
                    new GuestLabel(label.name(), label.documentType(), label.documentNumber())));
    return found;
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
